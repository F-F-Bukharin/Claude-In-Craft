package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.safety.WorldText;
import com.example.claudeplayer.core.work.TradeRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * trade_offers and trade: both need a villager (or wandering trader) within 6 blocks. trade does the vanilla trade
 * without a menu: the costs leave the main inventory, the result goes in (or drops at the body's feet), and
 * {@code notifyTrade} records it (uses, villager XP and level-up, the XP orb, the advancement and Forge's event).
 * A villager's prices for the body (gossip reputation, Hero of the Village) apply to both, as in a menu, and are reset
 * afterwards. It never spends more than trade_max_emeralds in one call, counted on the discounted price. Nothing here
 * harms the villager. The emerald math, the
 * counts and the texts are TradeRules (core, unit-tested).
 */
final class TradeActions {
    static final double TRADE_REACH = 6.0;

    private TradeActions() {
    }

    static ActionRunner.Handle offers(AgentRuntime rt, AgentPlayer p, Action.TradeOffers a) {
        return ActionRunner.Handle.done(listOffers(p, a.entityId()));
    }

    static ActionRunner.Handle trade(AgentRuntime rt, AgentPlayer p, Action.Trade a) {
        return ActionRunner.Handle.done(buy(rt, p, a));
    }

    private static ActionResult listOffers(AgentPlayer p, int entityId) {
        AbstractVillager v = villager(p, entityId);
        if (v == null) return ActionResult.failed("not_a_villager");
        if (p.distanceTo(v) > TRADE_REACH) return ActionResult.failed("too_far");
        MerchantOffers offers = v.getOffers();
        if (offers.isEmpty()) return ActionResult.failed("no_offers");
        // The prices trade would pay: the body's discounts, applied for the read and reset right after (as stopTrading
        // does). Under another player's open menu the offers already carry that player's prices and are left alone.
        boolean discount = !v.isTrading() && v instanceof Villager;
        if (discount) specialPrices((Villager) v, p);
        List<TradeRules.Offer> listed = new ArrayList<>();
        try {
            for (MerchantOffer o : offers) listed.add(rules(o));
        } finally {
            if (discount) for (MerchantOffer o : offers) o.resetSpecialPriceDiff();
        }
        return ActionResult.ok(TradeRules.offersText(header(v), listed));
    }

    private static ActionResult buy(AgentRuntime rt, AgentPlayer p, Action.Trade a) {
        AbstractVillager v = villager(p, a.entityId());
        if (v == null) return ActionResult.failed("not_a_villager");
        if (p.distanceTo(v) > TRADE_REACH) return ActionResult.failed("too_far");
        MerchantOffers offers = v.getOffers();
        if (a.offer() < 0 || a.offer() >= offers.size()) return ActionResult.failed("bad_input:offer");
        // A player has its trading screen open: buying under them would change what they see.
        if (v.isTrading() && v.getTradingPlayer() != p) return ActionResult.failed("busy");
        MerchantOffer offer = offers.get(a.offer());
        Inventory inv = p.getInventory();

        boolean wasTrading = v.isTrading();
        if (!wasTrading) {
            // As a player's menu would: notifyTrade credits the trader, and the villager's prices for this body apply.
            // setTradingPlayer(null) below runs Villager.stopTrading, which resets every offer's special price.
            if (v instanceof Villager villager) specialPrices(villager, p);
            v.setTradingPlayer(p);
        }
        int done = 0;
        TradeRules.Offer seen;
        String stop;
        try {
            seen = rules(offer); // the discounted price: the emerald cap is planned on what is really paid
            TradeRules.Plan plan = TradeRules.plan(seen, a.times(), carried(inv, offer), rt.settings().tradeMaxEmeralds());
            if (plan.times() <= 0) return ActionResult.failed(plan.stop());
            stop = plan.stop();
            for (int i = 0; i < plan.times(); i++) {
                if (offer.isOutOfStock()) {
                    stop = "out_of_stock";
                    break;
                }
                if (!pay(inv, offer.getCostA(), offer.getCostB())) {
                    stop = "missing_payment";
                    break;
                }
                give(p, offer.assemble());
                v.notifyTrade(offer); // increases the uses itself (checked with javap): no increaseUses() here
                p.awardStat(Stats.TRADED_WITH_VILLAGER);
                done++;
            }
        } finally {
            if (!wasTrading) v.setTradingPlayer(null);
            inv.setChanged();
        }
        if (done == 0) return ActionResult.failed(stop);
        return ActionResult.ok(TradeRules.boughtText(seen, done, stop));
    }

    /**
     * Villager's private updateSpecialPrices (checked with javap), which its private startTrading runs before a menu
     * opens: the body's gossip reputation, then the Hero of the Village discount.
     */
    private static void specialPrices(Villager v, AgentPlayer p) {
        int reputation = v.getPlayerReputation(p);
        if (reputation != 0) {
            for (MerchantOffer o : v.getOffers()) o.addToSpecialPriceDiff(-Mth.floor(reputation * o.getPriceMultiplier()));
        }
        MobEffectInstance hero = p.getEffect(MobEffects.HERO_OF_THE_VILLAGE);
        if (hero != null) {
            double share = 0.3 + 0.0625 * hero.getAmplifier();
            for (MerchantOffer o : v.getOffers()) {
                int off = (int) Math.floor(share * o.getBaseCostA().getCount());
                o.addToSpecialPriceDiff(-Math.max(off, 1));
            }
        }
    }

    private static @Nullable AbstractVillager villager(AgentPlayer p, int entityId) {
        Entity e = p.serverLevel().getEntity(entityId);
        return e instanceof AbstractVillager v && v.isAlive() ? v : null;
    }

    /** "librarian level 2", "wandering_trader", with a custom name (cleaned) when it has one. */
    private static String header(AbstractVillager v) {
        String what = v instanceof Villager villager
                ? villager.getVillagerData().getProfession().name() + " level " + villager.getVillagerData().getLevel()
                : SnapshotBuilder.typeId(v);
        String name = SnapshotBuilder.customName(v);
        return name == null ? what : "\"" + name + "\" " + what;
    }

    private static TradeRules.Offer rules(MerchantOffer o) {
        ItemStack a = o.getCostA();
        ItemStack b = o.getCostB();
        ItemStack r = o.getResult();
        return new TradeRules.Offer(SnapshotBuilder.itemId(a), a.getCount(),
                b.isEmpty() ? null : SnapshotBuilder.itemId(b), b.isEmpty() ? 0 : b.getCount(),
                SnapshotBuilder.itemId(r), r.getCount(), extra(r), o.getMaxUses() - o.getUses());
    }

    /** Enchantments ("sharpness 3, mending 1") and a custom name, cleaned; null when there is neither. */
    private static @Nullable String extra(ItemStack r) {
        List<String> parts = new ArrayList<>();
        if (r.hasCustomHoverName()) parts.add("\"" + WorldText.clean(r.getHoverName().getString(), WorldText.NAME_MAX) + "\"");
        String ench = EnchantmentHelper.getEnchantments(r).entrySet().stream()
                .map(e -> shortId(BuiltInRegistries.ENCHANTMENT.getKey(e.getKey())) + " " + e.getValue())
                .collect(Collectors.joining(", "));
        if (!ench.isEmpty()) parts.add(ench);
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String shortId(@Nullable ResourceLocation id) {
        if (id == null) return "unknown";
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }

    /** The carried count of each cost, matched as vanilla matches trade costs, keyed by the cost's id. */
    private static Map<String, Integer> carried(Inventory inv, MerchantOffer offer) {
        Map<String, Integer> counts = new HashMap<>();
        ItemStack a = offer.getCostA();
        ItemStack b = offer.getCostB();
        if (!a.isEmpty()) counts.put(SnapshotBuilder.itemId(a), count(inv, a));
        if (!b.isEmpty()) counts.put(SnapshotBuilder.itemId(b), count(inv, b));
        return counts;
    }

    /** MerchantOffer's private isRequiredItem: the same item, and the cost's tag (if any) contained in the stack's. */
    private static boolean matches(ItemStack cost, ItemStack have) {
        if (have.isEmpty() || !ItemStack.isSameItem(have, cost)) return false;
        return !cost.hasTag() || (have.hasTag() && NbtUtils.compareNbt(cost.getTag(), have.getTag(), false));
    }

    private static int count(Inventory inv, ItemStack cost) {
        int n = 0;
        for (ItemStack s : inv.items) if (matches(cost, s)) n += s.getCount();
        return n;
    }

    /** Takes one trade's costs from the main inventory, possibly from several stacks; all or nothing. */
    private static boolean pay(Inventory inv, ItemStack costA, ItemStack costB) {
        boolean same = !costB.isEmpty() && ItemStack.isSameItemSameTags(costA, costB);
        if (same) {
            if (count(inv, costA) < costA.getCount() + costB.getCount()) return false;
        } else {
            if (count(inv, costA) < costA.getCount()) return false;
            if (!costB.isEmpty() && count(inv, costB) < costB.getCount()) return false;
        }
        shrink(inv, costA, costA.getCount());
        if (!costB.isEmpty()) shrink(inv, costB, costB.getCount());
        return true;
    }

    private static void shrink(Inventory inv, ItemStack cost, int amount) {
        int left = amount;
        for (int i = 0; i < inv.items.size() && left > 0; i++) {
            ItemStack s = inv.items.get(i);
            if (!matches(cost, s)) continue;
            int n = Math.min(left, s.getCount());
            s.shrink(n);
            left -= n;
        }
    }

    /** Into the inventory; what doesn't fit drops at the body's feet. */
    private static void give(AgentPlayer p, ItemStack result) {
        result.onCraftedBy(p.level(), p, result.getCount());
        p.getInventory().add(result); // leaves in result what didn't fit
        if (result.isEmpty()) return;
        ItemEntity drop = new ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), result);
        drop.setDefaultPickUpDelay();
        p.level().addFreshEntity(drop);
    }
}
