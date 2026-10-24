package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;

/**
 * trade_offers and trade: both need a villager within 6, and trade never spends more than trade_max_emeralds per
 * call. trade_offers fails not_a_villager, too_far, no_offers; trade fails out_of_stock, missing_payment, over_limit.
 * Contract: the tool descriptions in core/brain/ToolSchemas.java and the records Action.TradeOffers and Action.Trade
 * in core/act/Action.java. This is a stub that the owning team replaces; it does nothing yet.
 */
final class TradeActions {
    private TradeActions() {
    }

    static ActionRunner.Handle offers(AgentRuntime rt, AgentPlayer p, Action.TradeOffers a) {
        return ActionRunner.Handle.done(ActionResult.failed("not_implemented"));
    }

    static ActionRunner.Handle trade(AgentRuntime rt, AgentPlayer p, Action.Trade a) {
        return ActionRunner.Handle.done(ActionResult.failed("not_implemented"));
    }
}
