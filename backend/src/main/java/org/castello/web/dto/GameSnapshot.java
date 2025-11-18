package org.castello.web.dto;

import java.util.List;
import java.util.Map;

public record GameSnapshot(
        String id,
        String status,
        int raid,
        String phase,
        WeatherView weather,
        List<PlayerView> players,
        List<CenterView> center,
        Map<String, RaidEffectsView> raidEffects,
        Map<String, List<StatModView>> raidMods,
        boolean hasUpcomingCombat,
        List<String> readyForPhase3,

        Long phase4DeadlineMillis,
        List<String> readyForNextRaid,
        List<TradeView> trades,

        DecksView decks,
        BiteView currentBite,

        List<RoundFightView> combatsQueue,
        Integer currentCombatIndex,
        RoundFightView currentCombat,
        Map<String, List<String>> unstableEligibleTargets,
        Map<String, List<String>> unstableEligibleLocations,
        Map<String, String>       unstableTargetByPlayer,
        Map<String, String>       unstableHarvestLocByPlayer,

        List<HistoryItemView> history,
        List<String> messages,
        long ts,
        String whoami
) {
    public record WeatherView(Integer roll, String status, String nameFr, String descFr) {}
    public record PlayerView(
            String id, String username, String role,
            int hp, int corruption,
            List<String> potions,
            String attackDice, String defenseDice,
            int wood, int herbs, int stone, int iron,
            int water, int gold, int souls, int silver,
            List<String> hand
    ) {}

    public record DecksView(Pile actionsVamp, Pile actionsHunters, Pile potions) {
        public record Pile(int left, int discard) {}
    }

    public record BiteView(String attackerId, String targetId, String location,
                           Integer roll, Long resolvedAtMillis) {}

    public record CenterView(String playerId, String card, boolean faceUp) {}
    public record StatModView(String stat, int amount, String source) {}

    public record RaidEffectsView(
            boolean invulnerable,
            boolean doubleAttack,
            boolean doubleDefense,
            boolean focus,
            boolean leech,
            boolean invisible,
            boolean rapid
    ) {}

    public record RoundFightView(
            String id, String location,
            String attackerId, String defenderId,
            Integer attackerRoll, Integer defenderRoll,
            Integer attackerFirstRoll, Integer defenderFirstRoll,
            Integer attackerReroll, Integer defenderReroll,
            Long resolvedAtMillis,
            List<String> breakdownLines
    ) {}
    public record HistoryItemView(long ts, int raid, String phase, String text) {}

    public record TradeView(
            String id, String side, String aId, String bId,
            Map<String,Integer> offerA, Map<String,Integer> offerB,
            String statusA, String statusB, long updatedAt
    ) {}
}