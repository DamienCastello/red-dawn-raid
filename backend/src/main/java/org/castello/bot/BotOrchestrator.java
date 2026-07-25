package org.castello.bot;

import jakarta.annotation.PostConstruct;
import org.castello.game.Game;
import org.castello.game.GameService;
import org.castello.game.GameStatus;
import org.castello.game.Phase;
import org.castello.game.support.GameStore;
import org.castello.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Boucle de pilotage des bots : un tick périodique balaie les parties, et
 * pour chaque partie contenant des bots, demande à {@link BotBrain} de jouer
 * AU PLUS UNE action (la première nécessaire). Le tick suivant rejoue — c'est
 * à la fois la garantie anti-blocage et un rythme « humain » minimal.
 *
 * Rythme : l'orchestrateur mesure depuis combien de temps l'état OBSERVABLE
 * de chaque partie n'a pas changé (empreinte ci-dessous) et transmet cette
 * « stabilité » au cerveau, qui exige un délai minimal par type d'action.
 * But : laisser le front des humains jouer ses animations et faire lui-même
 * les progressions automatiques — le bot n'est alors qu'un filet de sécurité.
 *
 * Les refus du serveur (CONFLICT/FORBIDDEN) sont normaux : l'état a pu changer
 * entre la lecture et l'action (un humain a agi, un timer a avancé la phase).
 * On les ignore, le tick suivant repart d'un état frais.
 */
@Service
public class BotOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BotOrchestrator.class);

    /**
     * Cadence du tick — la granularité des délais de BotBrain. Un délai n'est
     * constaté qu'au tick SUIVANT le changement d'état : le délai réel minimal
     * vaut donc ~2 ticks. À 1 200 ms, un jet « 0,5 s » partait en réalité à
     * ~2,4 s (mesuré par le game designer) → 400 ms pour des jets à ~0,5-0,8 s.
     * Coût : readAll 2,5×/s au lieu de 0,8×/s — acceptable à l'échelle
     * actuelle (cf. note « à optimiser (event-driven) » dans tick()).
     */
    private static final Duration TICK = Duration.ofMillis(400);

    private final GameStore store;
    private final BotBrain brain;
    private final GameService games;
    private final OpponentModel opponent;
    private final TaskScheduler scheduler;

    /** Empreinte d'état + date de première observation, par partie. */
    private record Observed(String fingerprint, long sinceMillis) {
    }

    private final Map<String, Observed> observedByGame = new ConcurrentHashMap<>();

    /**
     * Rotation du point de départ de la boucle des bots (round-robin), par
     * partie : avec « une seule action par tick » et un ordre FIXE, un bot en
     * fin de liste peut être AFFAMÉ — p. ex. le vampire jamais évalué pendant
     * que 5 chasseurs enchaînent leurs poses de pièges tick après tick, jusqu'à
     * l'expiration du timer de préphase → sa fenêtre de riposte (Présence
     * écrasante…) n'existe jamais. La rotation garantit à chacun ~1 tick sur N.
     */
    private final Map<String, Integer> rotationByGame = new ConcurrentHashMap<>();

    public BotOrchestrator(GameStore store, BotBrain brain, GameService games,
            OpponentModel opponent,
            @Qualifier("botTaskScheduler") TaskScheduler scheduler) {
        this.store = store;
        this.brain = brain;
        this.games = games;
        this.opponent = opponent;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void start() {
        scheduler.scheduleWithFixedDelay(this::tick, TICK);
    }

    void tick() {
        try {
            long now = System.currentTimeMillis();
            Set<String> seen = new HashSet<>();

            // NB : lecture de toutes les parties à chaque tick — acceptable à
            // l'échelle actuelle ; à optimiser (event-driven) si besoin un jour.
            for (Game g : store.readAll()) {
                List<Player> bots = g.getPlayers().stream()
                        .filter(Player::isBot)
                        .filter(p -> !p.isLeftGame())
                        .toList();
                if (bots.isEmpty())
                    continue;
                seen.add(g.getId());

                if (g.getStatus() == GameStatus.STARTING) {
                    ensureBotsReadyForStart(g, bots);
                    continue;
                }
                if (g.getStatus() != GameStatus.ACTIVE)
                    continue;

                // Modèle d'adversaire : mémoriser les lieux du raid une fois révélés
                // (info publique) → paris informés du bot (traque, clones…).
                opponent.observe(g);

                long stableMs = updateStability(g, now);
                boolean humans = g.getPlayers().stream()
                        .anyMatch(p -> !p.isBot() && !p.isLeftGame());

                // Une seule action par partie et par tick : évite que deux bots
                // se marchent dessus sur le même verrou dans le même tick.
                // Round-robin sur le point de départ : sans ça, un bot en fin de
                // liste est affamé quand ceux d'avant enchaînent (cf. rotationByGame).
                // EXCEPTION PHASE1/PHASE4 (réglage game designer) : les choix de
                // lieu (face cachée) et les achats/dépôts sont indépendants et
                // sans animation à préserver → chaque bot peut agir dans le même
                // tick, sinon 5-6 actions en série font traîner la phase
                // (~1,2 s chacune).
                boolean multiPerTick = g.getPhase() == Phase.PHASE1 || g.getPhase() == Phase.PHASE4;
                int start = rotationByGame.merge(g.getId(), 1, Integer::sum) % bots.size();
                for (int i = 0; i < bots.size(); i++) {
                    Player bot = bots.get((start + i) % bots.size());
                    if (tryPlay(g, bot, stableMs, humans) && !multiPerTick)
                        break;
                }
            }

            // Purge des parties disparues/terminées
            observedByGame.keySet().retainAll(seen);
            rotationByGame.keySet().retainAll(seen);
            opponent.retainOnly(seen);
        } catch (Exception e) {
            log.warn("bot tick failed", e);
        }
    }

    /**
     * Met à jour l'empreinte d'état de la partie et renvoie depuis combien de
     * millisecondes cet état est resté identique.
     */
    private long updateStability(Game g, long now) {
        String fp = fingerprint(g);
        Observed prev = observedByGame.get(g.getId());
        if (prev == null || !prev.fingerprint().equals(fp)) {
            observedByGame.put(g.getId(), new Observed(fp, now));
            return 0;
        }
        return now - prev.sinceMillis();
    }

    /**
     * Empreinte de l'état OBSERVABLE qui conditionne les actions des bots :
     * si l'un de ces éléments change, les délais de rythme repartent de zéro.
     */
    private String fingerprint(Game g) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(g.getRaid()).append('|').append(g.getPhase()).append('|')
                .append(g.getWeatherRoll()).append('|')
                .append(g.getCenter() != null ? g.getCenter().size() : 0).append('|')
                .append(g.getReadyForPhase3() != null ? g.getReadyForPhase3().size() : 0).append('|')
                .append(g.getReadyForNextRaid() != null ? g.getReadyForNextRaid().size() : 0).append('|')
                .append(g.getUnstableEligibleTargets() != null ? g.getUnstableEligibleTargets().size() : 0)
                .append('|')
                .append(g.getUnstableEligibleLocations() != null ? g.getUnstableEligibleLocations().size() : 0)
                .append('|')
                .append(g.getLocationEffectPending()).append('|')
                .append(g.getCurrentLocationEffectIndex()).append('|')
                .append(g.getLocationEffectChoice()).append('|')
                // draft d'Expérimentation : sa pose réinitialise la stabilité →
                // délai visible avant que le bot n'applique l'effet (sensation humaine)
                .append(g.getLaboratoryDraftLocation()).append('|');

        var a = g.getCurrentAction();
        if (a != null) {
            // targetId inclus : la sélection d'une cible (chooseNetTarget, draft…)
            // réinitialise la stabilité → délai visible avant le jet (sensation humaine)
            sb.append(a.getMode()).append(',').append(a.getTargetId()).append(',')
                    .append(a.getRoll()).append(',').append(a.getResolvedAtMillis());
        }
        sb.append('|');

        var c = g.getCurrentCombat();
        if (c != null) {
            sb.append(c.getId()).append(',').append(c.getAttackerRoll()).append(',')
                    .append(c.getDefenderRoll()).append(',').append(c.getResolvedAtMillis());
        }
        sb.append('|');

        var b = g.getCurrentBite();
        if (b != null) {
            sb.append(b.getRoll()).append(',').append(b.getArmorRoll()).append(',')
                    .append(b.getResolvedAtMillis());
        }
        return sb.toString();
    }

    /** Filet de sécurité : normalement requestStart a déjà marqué les bots prêts. */
    private void ensureBotsReadyForStart(Game g, List<Player> bots) {
        var ready = g.getReadyForStart();
        for (Player bot : bots) {
            if (ready == null || !ready.contains(bot.getId())) {
                try {
                    games.bootReady(g.getId(), bot.getId());
                } catch (Exception e) {
                    log.debug("bot bootReady refused for {} in {}: {}", bot.getId(), g.getId(), e.getMessage());
                }
                return; // un seul par tick
            }
        }
    }

    private boolean tryPlay(Game g, Player bot, long stableMs, boolean humans) {
        try {
            return brain.playOneAction(g, bot, stableMs, humans);
        } catch (ResponseStatusException e) {
            // Refus attendu (état changé entre-temps) : on réessaiera au prochain tick
            log.debug("bot action refused for {} in {} ({}): {}",
                    bot.getId(), g.getId(), g.getPhase(), e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("bot action failed for {} in {} ({})", bot.getId(), g.getId(), g.getPhase(), e);
            return false;
        }
    }
}
