package org.castello.web.dto;

import org.castello.game.LocationEffectChoice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record GameSnapshot(
                String id,
                String status,
                String winnerSide,
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
                Map<String, String> unstableTargetByPlayer,
                Map<String, String> unstableHarvestLocByPlayer,

                ActionView currentAction,

                List<String> ambushLocations,
                List<String> garlicBlockedLocations,
                List<String> trackerHunters,
                List<String> campfireLocations,
                List<String> netHunters,
                List<String> pitHunters,
                boolean hunterActionsBlockedThisRaid,
                List<String> clonesLocations,
                Boolean clonesFaceUp,
                String mirrorOwnerId,
                List<String> mirrorAltLocations,
                String mirrorChosenLocation,
                boolean shopPricesIncreasedThisRaid,
                Map<String, Integer> actionCardsBoughtThisRaid,

                String pendingConstructionInfra,
                List<String> builtInfras,
                boolean locationEffectPending,
                String locationEffectChoice, // ex: "STUDY" | "THEFT" | "OMEN" | null
                String locationEffectOwnerId, // id du joueur concerné ou null
                String locationEffectInfra, // ex: "LIBRARY" ou null
                List<String> libraryOmenCards,
                List<MonsterView> monsters,
                String laboratoryDraftMonsterType,
                String laboratoryDraftLocation,
                Integer laboratoryExplosionRoll,
                boolean ballroomBloodWaltz,
                List<Integer> ballroomWaltzRolls,
                Integer ballroomWaltzBest,
                boolean altarCorrupted,
                Integer bankLevel,
                Integer bankStoneProgress,
                java.util.Map<String, String> shopWeaponOfferTypeByHunter,
                java.util.Map<String, Integer> shopWeaponOfferTierByHunter,

                List<HistoryItemView> history,
                List<String> messages,
                int initialPlayerCount,
                long ts,
                String whoami) {

        public record WeatherView(
                        Integer roll,
                        String status,
                        String nameFr,
                        String descFr,
                        String secondaryStatus,
                        String secondaryNameFr,
                        String secondaryDescFr,
                        String thirdStatus,
                        String thirdNameFr,
                        String thirdDescFr) {
        }

        public record PlayerView(
                        String id, String username,
                        boolean leftGame,
                        String role,
                        List<String> hand,
                        List<String> potions,
                        List<String> elixirs,
                        List<String> actions,
                        int hp, int corruption,
                        String attackDice, String defenseDice,
                        String weapon, String armor,
                        int wood, int herbs, int stone, int iron,
                        int water, int gold, int souls, int silver,
                        boolean isBlessedStake,
                        boolean isSacredRosary,
                        boolean charismaticThisRaid,
                        boolean merchantPending,
                        Integer merchantRoll,
                        String shopBonusKind,
                        String shopBonusEquipId,
                        Integer shopBonusEquipTier,
                        boolean shopBonusBuyPending,
                        boolean elixirUsedThisRaid,
                        boolean crateUsedThisRaid,
                        boolean resourceBoughtThisRaid,
                        boolean advancedTransmutationUsedThisRaid,
                        boolean merchantUsedThisRaid) {
        }

        public record DecksView(Pile actionsVamp, Pile actionsHunters, Pile potions, Pile elixirs) {
                public record Pile(int deck, int discard, List<String> discardCards) {
                }
        }

        public record BiteView(String attackerId, String targetId, String location,
                        Integer roll, Integer armorRoll, Long resolvedAtMillis,
                        Boolean becameServant) {
        }

        public record ActionView(String mode, String ownerId, String location, String targetId,
                        Integer roll, List<String> breakdownLines, Long resolvedAtMillis) {
        }

        public record CenterView(String playerId, String card, boolean faceUp) {
        }

        public record StatModView(String stat, int amount, String source) {
        }

        public record RaidEffectsView(
                        boolean focus,
                        boolean leech,
                        boolean invulnerable,
                        boolean doubleAttack,
                        boolean doubleDefense,
                        boolean invisible,
                        boolean rapid) {
        }

        public record RoundFightView(
                        String id, String location,
                        String attackerId, String defenderId,
                        Integer attackerRoll, Integer defenderRoll,
                        Integer attackerFirstRoll, Integer defenderFirstRoll,
                        Integer attackerReroll, Integer defenderReroll,
                        Long resolvedAtMillis,
                        List<String> breakdownLines,
                        boolean cloneAttack,
                        boolean canBite) {
        }

        public record HistoryItemView(long ts, int raid, String phase, String text) {
        }

        public record TradeView(
                        String id, String side, String aId, String bId,
                        Map<String, Integer> offerA, Map<String, Integer> offerB,
                        String statusA, String statusB, long updatedAt) {
        }

        public record MonsterView(String id, String type, String location,
                        int hp, String attackDice, String defenseDice) {
        }

}