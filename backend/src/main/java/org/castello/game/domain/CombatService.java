package org.castello.game.domain;

import org.castello.game.CenterBoard;
import org.castello.game.Game;
import org.castello.game.GameStatus;
import org.castello.game.Infra;
import org.castello.game.Location;
import org.castello.game.Phase;
import org.castello.game.Potion;
import org.castello.game.RaidEffects;
import org.castello.game.RoundFight;
import org.castello.game.StatMod;
import org.castello.game.WeatherStatus;
import org.castello.game.support.Dice;
import org.castello.game.support.GameStore;
import org.castello.game.support.RaidFlow;
import org.castello.live.LiveEvents;
import org.castello.player.Player;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Domaine Combat (PHASE3).
 *
 * Construit la file de duels (2 passes par paire chasseur/ennemi, clones,
 * duels d'instables, provocations, embuscades, rapidité), résout chaque jet
 * (rollDice : modificateurs, dégâts, vols, morsures déclenchées) puis
 * enchaîne les combats (combatContinue) jusqu'à la fin de la phase.
 * Contient aussi l'usage des potions (préphase, participants au combat).
 */
@Service
public class CombatService {

    private final GameStore store;
    private final Dice dice;
    private final DeckService decks;
    private final EquipmentService equipment;
    private final CorruptionService corruption;
    private final LocationEffectService locationEffects;
    private final ActionCardService actionCards;
    private final ConstructionService construction;
    private final WeatherService weather;
    private final HarvestService harvest;
    private final LiveEvents live;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final RaidFlow flow;

    public CombatService(GameStore store, Dice dice, DeckService decks,
            EquipmentService equipment, CorruptionService corruption,
            LocationEffectService locationEffects, ActionCardService actionCards,
            ConstructionService construction, WeatherService weather, HarvestService harvest,
            LiveEvents live, org.springframework.transaction.PlatformTransactionManager tm,
            @Lazy RaidFlow flow) {
        this.weather = weather;
        this.harvest = harvest;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(tm);
        this.store = store;
        this.dice = dice;
        this.decks = decks;
        this.equipment = equipment;
        this.corruption = corruption;
        this.locationEffects = locationEffects;
        this.actionCards = actionCards;
        this.construction = construction;
        this.live = live;
        this.flow = flow;
    }

    private boolean isAlive(Player p) {
        return p.isAlive();
    }

    private void pushLive(Game g, String msg) {
        if (g.getMessages() == null)
            g.setMessages(new ArrayList<>());
        g.getMessages().add(msg);
        live.message(g, msg);
    }

    public int diceSides(String d) {
        if (d == null)
            return 6;
        return switch (d.toUpperCase()) {
            case "D4" -> 4;
            case "D6" -> 6;
            case "D8" -> 8;
            case "D10" -> 10;
            case "D12" -> 12;
            case "D20" -> 20;
            default -> 6;
        };
    }

    private String nameOf(Game g, String playerId) {
        return g.nameOf(playerId);
    }

    private String weatherNameFr(WeatherStatus ws) {
        return weather.weatherNameFr(ws);
    }

    private String weatherDescFr(WeatherStatus ws) {
        return weather.weatherDescFr(ws);
    }

    /**
     * Ajoute (ou remplace par source) un mod de raid pour un joueur.
     * Idempotent par 'source' : si un mod avec la même source existe, il est retiré
     * avant ajout.
     */
    /**
     * Construit la liste des modificateurs “moteur” d’un joueur pour le raid
     * courant.
     * On renvoie uniquement les mods chiffrés (ATTACK / DEFENSE) utiles pour le
     * calcul,
     * en appliquant éventuellement l’annulation météo par Feu de camp.
     *
     * Pour les monstres :
     * - on NE garde que les mods d'actions (source "ACTION:...")
     * - pas de météo / corruption / potions.
     */
    public List<StatMod> modsAppliedFor(Game g, String playerId, String stat, String location) {
        // 0) pas de mods → liste vide
        if (g.getRaidMods() == null || stat == null)
            return java.util.List.of();

        var list = g.getRaidMods().get(playerId);
        if (list == null)
            return java.util.List.of();

        // Est-ce un monstre ?
        boolean isMonster = (g.findMonster(playerId) != null);

        // On filtre d'abord par stat (ATTACK ou DEFENSE)
        var stream = list.stream()
                .filter(m -> stat.equalsIgnoreCase(m.getStat()));

        // CAS MONSTRE : uniquement les actions (Filet, Fosse, etc.)
        if (isMonster) {
            return stream
                    .filter(m -> {
                        String s = m.getSource();
                        // on ne garde pour l’instant que les sources ACTION:*
                        return s != null && s.startsWith("ACTION:");
                    })
                    .toList();
        }

        // CAS JOUEUR : comportement historique inchangé

        // 1) base = tous les mods pour ce joueur et cette stat
        var base = stream.toList();

        // 2) pas de lieu ou pas de feu de camp qui annule la météo → on renvoie tout
        if (location == null || !actionCards.isCampfireCancellingWeather(g, location)) {
            return base;
        }

        // 3) Feu de camp sur ce lieu : on retire seulement les mods météo
        return base.stream()
                .filter(m -> {
                    String s = m.getSource();
                    return s == null || !s.startsWith("WEATHER:");
                })
                .toList();
    }

    /**
     * Additionne les modificateurs d’un joueur pour une statistique donnée.
     *
     * Typiquement appelé au moment de résoudre un combat :
     * score = dX + totalModFor(g, playerId, ATTACK|DEFENSE)
     *
     * NB : ne crée rien, ne modifie rien — ne fait qu’agréger ce qui a été
     * préalablement construit (ex: par rebuildRaidModsForAll / modsAppliedFor).
     *
     */

    public int totalModFor(Game g, String playerId, String stat, String location) {
        return totalModForAt(g, playerId, stat, location);
    }

    public int totalModForAt(Game g, String playerId, String stat, String location) {
        return modsAppliedFor(g, playerId, stat, location)
                .stream().mapToInt(StatMod::getAmount).sum();
    }

    /**
     * Construit les logs liés aux mods (affichés dans la modale spectateur ET
     * poussés dans l'historique).
     */
    public List<String> buildModBreakdownLines(Game g, String playerId, String stat, int baseRoll, String location) {
        List<String> out = new ArrayList<>();
        int cur = baseRoll;
        String sideLabel = "ATTACK".equalsIgnoreCase(stat) ? "L’attaque" : "La défense";
        // entityName (pas nameOf) : l'entité peut être un MONSTRE (filet/fosse sur
        // un gardien) — nameOf renverrait son UUID brut dans l'historique.
        String name = g.entityName(playerId);

        for (var m : modsAppliedFor(g, playerId, stat, location)) {
            int delta = m.getAmount();
            if (delta == 0)
                continue;

            String verb = (delta >= 0) ? "augmente" : "diminue";
            int abs = Math.abs(delta);

            String src = "";
            String s = (m.getSource() == null) ? "" : m.getSource();
            if (m.getSource() != null && m.getSource().startsWith("WEATHER:")
                    && !actionCards.isCampfireCancellingWeather(g, location)) {
                try {
                    var wsStr = m.getSource().substring("WEATHER:".length());
                    var ws = WeatherStatus.valueOf(wsStr);
                    src = "par l’effet " + weatherNameFr(ws).toLowerCase();
                } catch (Exception ignored) {
                    /* fallback simple */ }
            } else if (s.startsWith("POTION:")) {
                String type = s.substring("POTION:".length());
                src = switch (type) {
                    case "FORCE" -> "par l'effet potion de force";
                    case "ENDURANCE" -> "par l'effet potion d’endurance";
                    case "VIE" -> "par l'effet potion de vie";
                    default -> "par l'effet potion";
                };
            } else if (s.startsWith("ACTION:")) {
                String type = s.substring("ACTION:".length());
                src = switch (type) {
                    case "NET", "NET:ENG" -> "par l'effet du filet";
                    case "PIT", "PIT:ENG" -> "par l'effet de la fosse";
                    case "LONELY", "LONELY:ENG" -> "par l'effet de solitaire";
                    case "AFFAIBLISSEMENT_OCCULTE", "AFFAIBLISSEMENT_OCCULTE:ENG" ->
                        "par l'effet d'affaiblissement occulte";
                    default -> "par l'effet action";
                };
            } else if (s.startsWith("CORRUPTION:")) {
                // L1 moteur (−1 ATK/DEF) => libellé clair
                if (s.contains(":L1:")) {
                    src = "par l’effet Affaibli (corruption)";
                }
                // L2 ("Instable") n’a pas de mod chiffré => pas de ligne ici (géré en chip côté
                // front)
            } else if (s.startsWith("EQUIP:")) {
                if ("EQUIP:VAMP_ARMOR_DEF".equals(s)) {
                    src = "grâce à son armure vampirique";
                }
            } else if (s.startsWith("HIT:")) {
                if ("HIT:STUN_WEAPON:ENG".equals(s)) {
                    src = "par l’effet étourdissement";
                }
            }

            cur += delta;
            out.add(String.format("%s de %s %s de %d %s et passe à %d", sideLabel, name, verb, abs, src, cur));
        }
        return out;
    }

    public void appendModBreakdownForSide(Game g,
            RoundFight r,
            String playerId,
            String stat, // "ATTACK" ou "DEFENSE"
            int baseRoll) { // valeur brute du dé (ou de la Valse/Foca)
        // On construit les lignes de breakdown pour ce côté
        List<String> lines = buildModBreakdownLines(
                g,
                playerId,
                stat,
                baseRoll,
                r.getLocation());

        if (lines == null || lines.isEmpty()) {
            return;
        }

        // On s'assure que la liste breakdownLines existe
        if (r.getBreakdownLines() == null) {
            r.setBreakdownLines(new java.util.ArrayList<>());
        }

        // On ajoute les lignes au breakdown du duel
        r.getBreakdownLines().addAll(lines);

        // Et on pousse aussi dans l'historique global
        for (String ln : lines) {
            g.addHistory(ln);
        }
    }

    // ---------- CRUD ----------
    public void buildCombatsQueue(Game g) {
        g.getCombatsQueue().clear();

        // Ensemble des joueurs instables déjà réaffectés (attaque ou récolte)
        var unstableAssigned = new java.util.HashSet<String>();
        if (g.getUnstableTargetByPlayer() != null) {
            unstableAssigned.addAll(g.getUnstableTargetByPlayer().keySet());
        }
        if (g.getUnstableHarvestLocByPlayer() != null) {
            unstableAssigned.addAll(g.getUnstableHarvestLocByPlayer().keySet());
        }

        var groups = g.playersByLocation();

        // 1) Combats par défaut : (ennemi ∈ {VAMPIRE, SERVANT, MONSTRE}) × (HUNTER non
        // réaffecté)
        for (var e : groups.entrySet()) {
            String loc = e.getKey();
            java.util.List<Player> onLoc = e.getValue();

            var enemiesPlayers = onLoc.stream()
                    .filter(p -> "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                    .filter(p -> isAlive(p))
                    .toList();

            var huntersForDefault = onLoc.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> isAlive(p))
                    .filter(p -> !unstableAssigned.contains(p.getId()))
                    .toList();

            // Monstres présents sur ce lieu
            var monstersHere = g.monstersOn(loc).stream()
                    .filter(m -> m.hp > 0)
                    .toList();

            // Noms des chasseurs engagés (pour la ligne d'historique "Combat — …")
            String huntersNames = String.join(", ",
                    huntersForDefault.stream().map(h -> g.nameOf(h.getId())).toList());

            // (A) Combats contre vampires/serviteurs (comme avant)
            for (var enemy : enemiesPlayers) {
                if (!huntersForDefault.isEmpty()) {
                    // Même format que l'aperçu de préphase (fil du centre)
                    g.addHistory("Combat — " + g.nameOf(enemy.getId()) + " VS "
                            + huntersNames + " à " + Location.labelFrOf(loc));
                }
                for (var h : huntersForDefault) {

                    // Marque ténébreuse : +1 corruption si chasseur marqué croise le vampire
                    if ("VAMPIRE".equals(enemy.getRole()) && corruption.isDarkMarked(g, h.getId())) {
                        corruption.applyDarkMarkCorruptionOncePerRaid(g, h);
                    }

                    boolean hunterInvisible = hasInvisibility(g, h.getId());
                    boolean enemyInvisible = hasInvisibility(g, enemy.getId());

                    if (hunterInvisible && !enemyInvisible) {
                        // Le chasseur invisible attaque une fois, défenseur sans dé
                        RoundFight r1 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, h.getId(), enemy.getId());
                        r1.setDefenderRoll(0); // l'ennemi ne lance pas de dé de défense
                        g.getCombatsQueue().add(r1);

                    } else if (enemyInvisible && !hunterInvisible) {
                        // L'ennemi invisible attaque une fois, chasseur sans dé
                        RoundFight r2 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, enemy.getId(), h.getId());
                        r2.setDefenderRoll(0);
                        g.getCombatsQueue().add(r2);

                    } else {
                        // Deux rounds aller/retour
                        RoundFight r1 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, h.getId(), enemy.getId());
                        if (hunterInvisible) {
                            r1.setDefenderRoll(0);
                        }
                        g.getCombatsQueue().add(r1);

                        RoundFight r2 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, enemy.getId(), h.getId());
                        if (enemyInvisible) {
                            r2.setDefenderRoll(0);
                        }
                        g.getCombatsQueue().add(r2);
                    }
                }
            }

            // (B) Combats contre monstres
            for (var m : monstersHere) {
                if (!huntersForDefault.isEmpty()) {
                    g.addHistory("Combat — " + m.type.labelFr() + " VS "
                            + huntersNames + " à " + Location.labelFrOf(loc));
                }
                for (var h : huntersForDefault) {

                    boolean hunterInvisible = hasInvisibility(g, h.getId());
                    // Les monstres n'ont pas (encore) de potions → jamais invisibles

                    if (hunterInvisible) {
                        // Le chasseur est invisible :
                        // il attaque une fois, le monstre ne lance PAS de dé de défense.
                        RoundFight r1 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, h.getId(), m.id);
                        r1.setDefenderRoll(0);
                        g.getCombatsQueue().add(r1);

                    } else {
                        // Cas classique : 2 rounds aller/retour
                        RoundFight r1 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, h.getId(), m.id);
                        g.getCombatsQueue().add(r1);

                        RoundFight r2 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, m.id, h.getId());
                        g.getCombatsQueue().add(r2);
                    }
                }
            }
        }

        // 2) Duels "instable -> cible"
        if (g.getUnstableTargetByPlayer() != null) {
            for (var entry : g.getUnstableTargetByPlayer().entrySet()) {
                String unstableId = entry.getKey();
                String targetId = entry.getValue();

                String loc = g.getCenter().stream()
                        .filter(cb -> cb.getPlayerId().equals(targetId))
                        .map(CenterBoard::getCard)
                        .findFirst()
                        .orElse("forest");

                RoundFight duel = new RoundFight(
                        java.util.UUID.randomUUID().toString(), loc, unstableId, targetId);
                // Si l’instable a bu Invisibilité, sa cible ne lancera pas de dé
                if (hasInvisibility(g, unstableId)) {
                    duel.setDefenderRoll(0);
                }
                g.getCombatsQueue().add(duel);

                // Message lisible
                String info = "Combat — " + g.nameOf(unstableId) + " VS "
                        + g.nameOf(targetId) + " à " + Location.labelFrOf(loc);
                if (g.getMessages() == null)
                    g.setMessages(new java.util.ArrayList<>());
                g.getMessages().add(info);
                g.addHistory(info);
            }
        }

        // 3) Clones des ombres : attaques supplémentaires du vampire
        var clonesLocs = g.getClonesLocations();
        if (clonesLocs != null && !clonesLocs.isEmpty()) {

            var vampOpt = g.vampire();
            if (vampOpt.isPresent()) {
                Player vamp = vampOpt.get();
                String vampId = vamp.getId();
                boolean vampInvisible = hasInvisibility(g, vampId);

                for (int i = 0; i < clonesLocs.size(); i++) {
                    String loc = clonesLocs.get(i);
                    boolean canBite = false;
                    if (g.getClonesBiteCapabilities() != null && i < g.getClonesBiteCapabilities().size()) {
                        canBite = Boolean.TRUE.equals(g.getClonesBiteCapabilities().get(i));
                    }

                    var onLoc = groups.get(loc);
                    if (onLoc == null || onLoc.isEmpty())
                        continue;

                    var huntersHere = onLoc.stream()
                            .filter(p -> "HUNTER".equals(p.getRole()))
                            .filter(p -> isAlive(p))
                            // on réutilise la même logique que pour les combats par défaut
                            .filter(p -> !unstableAssigned.contains(p.getId()))
                            .toList();

                    if (!huntersHere.isEmpty()) {
                        g.addHistory("Combat — clones d'ombre VS "
                                + String.join(", ", huntersHere.stream().map(h -> g.nameOf(h.getId())).toList())
                                + " à " + Location.labelFrOf(loc));
                    }

                    for (Player h : huntersHere) {
                        boolean hunterInvisible = hasInvisibility(g, h.getId());

                        RoundFight cloneFight = new RoundFight(
                                java.util.UUID.randomUUID().toString(),
                                loc,
                                vampId,
                                h.getId());
                        cloneFight.setCloneAttack(true); // on marque le round comme “clone”
                        cloneFight.setCanBite(canBite);

                        // si vampire invisible && pas le chasseur → pas de dé de défense
                        if (vampInvisible && !hunterInvisible) {
                            cloneFight.setDefenderRoll(0);
                        }

                        g.getCombatsQueue().add(cloneFight);
                    }
                }
            }
        }

        // 4) Provocation : certains ennemis ne peuvent attaquer qu'un chasseur précis
        var provokedMap = g.getProvokedTargetByEnemy();
        if (provokedMap != null && !provokedMap.isEmpty()) {
            java.util.List<RoundFight> filtered = new java.util.ArrayList<>();
            for (RoundFight rf : g.getCombatsQueue()) {
                String forcedDef = provokedMap.get(rf.getAttackerId());
                if (forcedDef == null) {
                    // attaquant non provoqué : round intact
                    filtered.add(rf);
                } else {
                    // attaquant provoqué : ne conserver que les attaques vers le chasseur
                    // provoquant
                    if (forcedDef.equals(rf.getDefenderId())) {
                        filtered.add(rf);
                    } else {
                        // attaque vers un autre défenseur : annulée
                    }
                }
            }
            g.setCombatsQueue(filtered);
        }

        // 5) Embuscade : la cible ne peut pas riposter contre les chasseurs embusqués
        var ambushMap = g.getAmbushHuntersByEnemy();
        if (ambushMap != null && !ambushMap.isEmpty()) {
            java.util.List<RoundFight> filtered2 = new java.util.ArrayList<>();
            for (RoundFight rf : g.getCombatsQueue()) {
                java.util.List<String> ambushHunters = ambushMap.get(rf.getAttackerId());
                if (ambushHunters == null) {
                    // attaquant non ciblé par Embuscade → round intact
                    filtered2.add(rf);
                } else {
                    // attaquant = la cible de l'Embuscade
                    boolean defIsAmbushHunter = ambushHunters.contains(rf.getDefenderId());

                    // On annule uniquement les ripostes "classiques" (pas les clones d'ombre)
                    if (defIsAmbushHunter && !rf.isCloneAttack()) {
                        // riposte annulée
                    } else {
                        filtered2.add(rf);
                    }
                }
            }
            g.setCombatsQueue(filtered2);
        }

        // 6) appliquer Potion de rapidité
        if (g.getRaidEffects() != null && !g.getRaidEffects().isEmpty()) {
            java.util.List<RoundFight> expanded = new java.util.ArrayList<>();
            for (RoundFight rf : g.getCombatsQueue()) {
                // round original inchangé
                expanded.add(rf);

                RaidEffects fx = g.getRaidEffects().get(rf.getAttackerId());
                if (fx != null && fx.isRapid()) {
                    // on duplique le round pour cet attaquant
                    RoundFight extra = new RoundFight(
                            java.util.UUID.randomUUID().toString(),
                            rf.getLocation(),
                            rf.getAttackerId(),
                            rf.getDefenderId());
                    // Marquer que c’est la 2ᵉ attaque (Potion de rapidité)
                    extra.setRapidExtra(true);

                    // Si l’attaquant a aussi Invisibilité, le round dupliqué
                    // a lui aussi un défenseur qui ne lance pas de dé
                    if (fx.isInvisible()) {
                        extra.setDefenderRoll(0);
                    }
                    expanded.add(extra);
                }
            }
            g.setCombatsQueue(expanded);
        }

        // 7) Pointeur sur le combat courant (plus aucun nextAdvanceAt/timer côté
        // serveur)
        if (!g.getCombatsQueue().isEmpty()) {
            g.setCurrentCombatIndex(0);
            g.setCurrentCombat(g.getCombatsQueue().get(0));
        } else {
            g.setCurrentCombatIndex(null);
            g.setCurrentCombat(null);
        }
    }

    private void appendBreakdown(RoundFight r, String line) {
        if (line == null || line.isBlank())
            return;
        if (r.getBreakdownLines() == null) {
            r.setBreakdownLines(new java.util.ArrayList<>());
        }
        r.getBreakdownLines().add(line);
    }

    @Transactional
    public Game rollDice(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE
                || g.getPhase() != Phase.PHASE3
                || g.getCurrentCombat() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in combat");
        }
        if (g.getCurrentBite() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "bite pending");
        }

        var r = g.getCurrentCombat();

        // --- Init du breakdown : on ne reset que si le duel démarre vraiment ---
        // (aucun jet encore posé pour ce RoundFight)
        boolean duelJustStarted = r.getAttackerRoll() == null
                && r.getDefenderRoll() == null
                && r.getAttackerFirstRoll() == null
                && r.getDefenderFirstRoll() == null;

        if (duelJustStarted) {
            if (r.getBreakdownLines() == null) {
                r.setBreakdownLines(new java.util.ArrayList<>());
            } else {
                r.getBreakdownLines().clear();
            }
        }

        // --- Joueur ou monstre ? ---
        Player attackerPlayer0 = g.getPlayers().stream()
                .filter(p -> p.getId().equals(r.getAttackerId()))
                .findFirst()
                .orElse(null);

        Player defenderPlayer0 = g.getPlayers().stream()
                .filter(p -> p.getId().equals(r.getDefenderId()))
                .findFirst()
                .orElse(null);

        Game.Monster attackerMonster = g.findMonster(r.getAttackerId());
        Game.Monster defenderMonster = g.findMonster(r.getDefenderId());

        boolean attackerIsPlayer = (attackerPlayer0 != null);
        boolean defenderIsPlayer = (defenderPlayer0 != null);
        boolean attackerIsMonster = (attackerMonster != null);
        boolean defenderIsMonster = (defenderMonster != null);

        // Effets de raid pour CE joueur (dont Potion de focalisation)
        RaidEffects fx = (g.getRaidEffects() != null) ? g.getRaidEffects().get(userId) : null;
        boolean hasFocus = (fx != null && fx.isFocus());

        // ---- Payloads d’événements à émettre APRÈS commit
        final class Ev {
            boolean sendAtk, sendDef, sendResolved, startBite;
            String roundId, atkId, defId, biteAtt, biteTgt, biteLoc;
            Integer atkRoll, defRoll, dmg, defenderHp;
            java.util.List<String> breakdown = java.util.List.of();
        }
        Ev ev = new Ev();
        ev.roundId = r.getId();

        // --- Pose du jet (attacker OU defender) avec gestion FOCALISATION
        if (userId.equals(r.getAttackerId())) {
            // ici : always un joueur (monstre n'appelle jamais /roll)
            if (attackerPlayer0 == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
            }

            // AUTEL — message "before" pour la purification par combat
            String altarCode = Infra.ALTAR.locationCode();
            boolean isAltarFight = altarCode != null && altarCode.equals(r.getLocation());

            if (isAltarFight
                    && locationEffects.isAltarBuilt(g)
                    && locationEffects.isAltarCorrupted(g) // on ne purifie que si l'autel est corrompu
                    && defenderPlayer0 != null
                    && "HUNTER".equals(attackerPlayer0.getRole()) // attaquant = chasseur
                    && "VAMPIRE".equals(defenderPlayer0.getRole())// défenseur = vampire
                    && !g.isAltarVampTookDamageThisRaid() // encore aucun dégât pris par le vampire sur l'autel
                    && !g.isAltarBiteOccurredThisRaid() // aucune morsure réussie sur l'autel
                    && r.getAttackerRoll() == null // premier jet pour ce duel
                    && r.getAttackerFirstRoll() == null) {

                String line = "Autel — "
                        + g.nameOf(r.getAttackerId())
                        + " invoque une lueur d'espoir: "
                        + "Il tente de repousser le vampire et mettre fin au rituel";

                g.addHistory(line);
                appendBreakdown(r, line);
            }

            int sides = diceSides(attackerPlayer0.getAttackDice());

            // --- Salle de bal : Valse sanguinaire ---
            boolean waltzFight = locationEffects.isBallroomBloodWaltzAttack(g, r, userId, attackerPlayer0, defenderPlayer0);

            int huntersCountOnBallroom = 0;
            if (waltzFight) {
                String ballroomCode = Infra.BALLROOM.locationCode();
                huntersCountOnBallroom = (int) g.getPlayers().stream()
                        .filter(pp -> "HUNTER".equals(pp.getRole()))
                        .filter(pp -> pp.getHp() > 0)
                        .filter(pp -> ballroomCode != null && ballroomCode.equals(g.locationOf(pp.getId())))
                        .count();
            }
            // Valse n’a un effet réel que s’il y a au moins 2 chasseurs
            boolean effectiveWaltz = waltzFight && huntersCountOnBallroom > 1;

            boolean handled = false;

            // ==========================
            // CAS 1 : Valse + Focalisation (2 étapes)
            // ==========================
            if (effectiveWaltz && hasFocus) {

                // Étape 1 : on tire tous les dés de Valse, on garde le meilleur comme "premier
                // résultat"
                if (r.getAttackerFirstRoll() == null && r.getAttackerRoll() == null) {

                    java.util.List<Integer> rolls = g.getBallroomBloodWaltzRolls();
                    Integer best = g.getBallroomBloodWaltzBestRoll();

                    if (rolls == null || best == null || rolls.size() != huntersCountOnBallroom) {
                        rolls = new java.util.ArrayList<>();
                        best = Integer.MIN_VALUE;
                        for (int i = 0; i < huntersCountOnBallroom; i++) {
                            int v = dice.roll(sides);
                            rolls.add(v);
                            if (v > best)
                                best = v;
                        }
                        g.setBallroomBloodWaltzRolls(rolls);
                        g.setBallroomBloodWaltzBestRoll(best); // meilleur dé de Valse pour tout le raid (base)
                    }

                    r.setBallroomWaltzRolls(new java.util.ArrayList<>(rolls));
                    r.setBallroomWaltzBest(best);
                    // Ce "best" devient le "premier résultat" de Focalisation
                    r.setAttackerFirstRoll(best);

                    g.addHistory(
                            "Salle de bal — Valse sanguinaire : " + g.nameOf(r.getAttackerId())
                                    + " lance " + rolls.size() + " dés d'attaque "
                                    + rolls + " (meilleur actuel = " + best + ").");

                    // On envoie quand même un event pour afficher ce premier résultat
                    ev.sendAtk = true;
                    ev.atkId = r.getAttackerId();
                    ev.atkRoll = best;

                    // Pas encore de résultat final : on attend un 2ᵉ /roll pour la Focalisation
                    handled = true;

                } else if (r.getAttackerFirstRoll() != null && r.getAttackerRoll() == null) {
                    // Étape 2 : dé de Focalisation, unique pour cette Valse
                    int focusRoll = dice.roll(sides);
                    r.setAttackerReroll(focusRoll);

                    int prevBest = r.getAttackerFirstRoll();
                    int finalBest = Math.max(prevBest, focusRoll);
                    r.setAttackerRoll(finalBest);

                    g.addHistory(
                            g.nameOf(r.getAttackerId())
                                    + " — relance d'attaque (Potion de focalisation) : "
                                    + prevBest + " → " + focusRoll
                                    + " (garde " + finalBest + ").");

                    ev.sendAtk = true;
                    ev.atkId = r.getAttackerId();
                    ev.atkRoll = finalBest;

                    appendModBreakdownForSide(g, r, r.getAttackerId(), "ATTACK", finalBest);

                    handled = true;
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                }
            }

            // ==========================
            // CAS 2 : Valse seule (pas de Focalisation)
            // ==========================
            if (!handled && effectiveWaltz && !hasFocus) {

                if (r.getAttackerRoll() != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                }

                java.util.List<Integer> rolls = g.getBallroomBloodWaltzRolls();
                Integer best = g.getBallroomBloodWaltzBestRoll();

                if (rolls == null || best == null || rolls.size() != huntersCountOnBallroom) {
                    rolls = new java.util.ArrayList<>();
                    best = Integer.MIN_VALUE;
                    for (int i = 0; i < huntersCountOnBallroom; i++) {
                        int v = dice.roll(sides);
                        rolls.add(v);
                        if (v > best)
                            best = v;
                    }
                    g.setBallroomBloodWaltzRolls(rolls);
                    g.setBallroomBloodWaltzBestRoll(best);
                }

                r.setBallroomWaltzRolls(new java.util.ArrayList<>(rolls));
                r.setBallroomWaltzBest(best);
                r.setAttackerRoll(best);

                g.addHistory(
                        "Salle de bal — Valse sanguinaire : " + g.nameOf(r.getAttackerId())
                                + " lance " + rolls.size() + " dés d'attaque "
                                + rolls + " et conserve le meilleur (" + best + ").");

                ev.sendAtk = true;
                ev.atkId = r.getAttackerId();
                ev.atkRoll = best;

                appendModBreakdownForSide(g, r, r.getAttackerId(), "ATTACK", best);

                handled = true;
            }

            // ==========================
            // CAS 3 : pas de Valse "effective" (≤1 chasseur) → comportement standard
            // ==========================
            if (!handled) {
                if (hasFocus) {
                    // FOCALISATION EXISTANT
                    if (r.getAttackerFirstRoll() == null && r.getAttackerRoll() == null) {
                        int roll1 = dice.roll(sides);
                        r.setAttackerFirstRoll(roll1);

                        g.addHistory(g.nameOf(r.getAttackerId())
                                + " — jet d'attaque (Potion de focalisation, premier dé) = " + roll1 + ".");

                        ev.sendAtk = true;
                        ev.atkId = r.getAttackerId();
                        ev.atkRoll = roll1;

                    } else if (r.getAttackerFirstRoll() != null && r.getAttackerRoll() == null) {
                        int roll2 = dice.roll(sides);
                        int first = r.getAttackerFirstRoll();
                        int best = Math.max(first, roll2);

                        r.setAttackerReroll(roll2);
                        r.setAttackerRoll(best);

                        String hist = g.nameOf(r.getAttackerId())
                                + " — relance d'attaque grâce à la Potion de focalisation : "
                                + first + " → " + roll2 + " (garde " + best + ").";
                        g.addHistory(hist);

                        // Breakdown "diff de Foca" dès maintenant (pas besoin d'attendre la résolution)
                        if (best > first) {
                            String diffLine = g.nameOf(r.getAttackerId())
                                    + " a gagné " + (best - first)
                                    + " de stat par l'effet Potion de focalisation.";
                            g.addHistory(diffLine);
                            appendBreakdown(r, diffLine);
                        }

                        ev.sendAtk = true;
                        ev.atkId = r.getAttackerId();
                        ev.atkRoll = best;

                        appendModBreakdownForSide(g, r, r.getAttackerId(), "ATTACK", best);
                    } else {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                    }

                } else {
                    // Pas de potion : jet simple
                    if (r.getAttackerRoll() == null) {
                        int roll = dice.roll(sides);
                        r.setAttackerRoll(roll);
                        g.addHistory(g.nameOf(r.getAttackerId()) + " — jet d'attaque = " + roll + ".");

                        ev.sendAtk = true;
                        ev.atkId = r.getAttackerId();
                        ev.atkRoll = roll;

                        appendModBreakdownForSide(g, r, r.getAttackerId(), "ATTACK", roll);
                    } else {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                    }
                }
            }
        } else if (userId.equals(r.getDefenderId())) {
            // ici : always un joueur (monstre n'appelle jamais /roll)
            var p = defenderPlayer0;
            if (p == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
            }
            int sides = diceSides(p.getDefenseDice());

            if (hasFocus) {
                if (r.getDefenderFirstRoll() == null && r.getDefenderRoll() == null) {
                    // 1er jet de défense, uniquement stocké comme "premier dé"
                    int roll1 = dice.roll(sides);
                    r.setDefenderFirstRoll(roll1);

                    g.addHistory(g.nameOf(r.getDefenderId())
                            + " — jet de défense (Potion de focalisation, premier dé) = " + roll1 + ".");

                    ev.sendDef = true;
                    ev.defId = r.getDefenderId();
                    ev.defRoll = roll1;

                } else if (r.getDefenderFirstRoll() != null && r.getDefenderRoll() == null) {
                    // 2e appel : on fixe le jet final (meilleur des deux)
                    int roll2 = dice.roll(sides);
                    int first = r.getDefenderFirstRoll();
                    int best = Math.max(first, roll2);

                    r.setDefenderReroll(roll2);
                    r.setDefenderRoll(best);

                    String hist = g.nameOf(r.getDefenderId())
                            + " — relance de défense grâce à la Potion de focalisation : "
                            + first + " → " + roll2 + " (garde " + best + ").";
                    g.addHistory(hist);

                    if (best > first) {
                        String diffLine = g.nameOf(r.getDefenderId())
                                + " a gagné " + (best - first)
                                + " de stat par l'effet Potion de focalisation.";
                        g.addHistory(diffLine);
                        appendBreakdown(r, diffLine);
                    }

                    ev.sendDef = true;
                    ev.defId = r.getDefenderId();
                    ev.defRoll = best;

                    appendModBreakdownForSide(g, r, r.getDefenderId(), "DEFENSE", best);
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                }

            } else {
                // Pas de potion : comportement initial
                if (r.getDefenderRoll() == null) {
                    int roll = dice.roll(sides);
                    r.setDefenderRoll(roll);
                    g.addHistory(g.nameOf(r.getDefenderId()) + " — jet de défense = " + roll + ".");
                    ev.sendDef = true;
                    ev.defId = r.getDefenderId();
                    ev.defRoll = roll;

                    appendModBreakdownForSide(g, r, r.getDefenderId(), "DEFENSE", roll);
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                }
            }

        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
        }

        // --- Auto-roll pour les monstres ---
        // Cas 1 : attaquant = monstre, défenseur = joueur
        if (attackerIsMonster && defenderIsPlayer
                && r.getDefenderRoll() != null && r.getAttackerRoll() == null) {

            int roll = rollMonsterAttack(attackerMonster);
            r.setAttackerRoll(roll);

            g.addHistory(g.entityName(r.getAttackerId())
                    + " — jet d'attaque automatique = " + roll + ".");

            appendModBreakdownForSide(g, r, r.getAttackerId(), "ATTACK", roll);

            ev.sendAtk = true;
            ev.atkId = r.getAttackerId();
            ev.atkRoll = roll;
        }

        // Cas 2 : défenseur = monstre, attaquant = joueur
        if (defenderIsMonster && attackerIsPlayer
                && r.getAttackerRoll() != null && r.getDefenderRoll() == null) {

            int roll = rollMonsterDefense(defenderMonster);
            r.setDefenderRoll(roll);

            g.addHistory(g.entityName(r.getDefenderId())
                    + " — jet de défense automatique = " + roll + ".");

            appendModBreakdownForSide(g, r, r.getDefenderId(), "DEFENSE", roll);

            ev.sendDef = true;
            ev.defId = r.getDefenderId();
            ev.defRoll = roll;
        }

        // --- Résolution si les 2 jets sont posés (et pas encore résolu)
        if (r.getAttackerRoll() != null && r.getDefenderRoll() != null && combatNotResolved(r)) {
            int rawAtk = r.getAttackerRoll();
            int rawDef = r.getDefenderRoll(); // 0 d’emblée si Invisibilité a joué lors de la création du duel

            String loc = r.getLocation();

            // Modificateurs pour joueurs ET monstres
            // - Pour un joueur : météo, corruption, potions, actions, etc.
            // - Pour un monstre : uniquement les ACTION:* (Filet, Fosse, etc.)
            // grâce au filtrage dans modsAppliedFor(...)
            int atkMod = totalModFor(g, r.getAttackerId(), "ATTACK", loc);
            int defMod = totalModFor(g, r.getDefenderId(), "DEFENSE", loc);

            // Effets de raid (potions) pour attaquant et défenseur (uniquement joueurs, de
            // fait)
            RaidEffects atkFx = (g.getRaidEffects() != null)
                    ? g.getRaidEffects().get(r.getAttackerId())
                    : null;

            RaidEffects defFx = (g.getRaidEffects() != null)
                    ? g.getRaidEffects().get(r.getDefenderId())
                    : null;

            var atkPlayer = g.getPlayers().stream()
                    .filter(p -> p.getId().equals(r.getAttackerId()))
                    .findFirst()
                    .orElse(null);

            var defPlayer = g.getPlayers().stream()
                    .filter(p -> p.getId().equals(r.getDefenderId()))
                    .findFirst()
                    .orElse(null);

            Game.Monster defMonster = g.findMonster(r.getDefenderId());

            boolean ecorceEvade = false;

            if (atkPlayer != null
                    && defPlayer != null
                    && "HUNTER".equals(atkPlayer.getRole())
                    && "VAMPIRE".equals(defPlayer.getRole())
                    && equipment.vampArmorHasEvasion(defPlayer.getArmor())
                    && rawAtk == 12) { // 12 sur le D12 du chasseur
                ecorceEvade = true;
            }

            int atkScoreBeforeMul = rawAtk + atkMod;
            int defScoreBeforeMul = rawDef + defMod;

            int atkScore = atkScoreBeforeMul;
            int defScore = defScoreBeforeMul;

            // Rage : x2 attaque
            if (atkFx != null && atkFx.isDoubleAttack()) {
                atkScore *= 2;
            }
            // Résilience : x2 défense
            if (defFx != null && defFx.isDoubleDefense()) {
                defScore *= 2;
            }

            int dmg = Math.max(0, atkScore - defScore);

            if (ecorceEvade) {
                dmg = 0;
                String line = "L'Écorce de la Nuit réagit au coup critique de "
                        + g.nameOf(atkPlayer.getId())
                        + " : le vampire se volatilise brièvement, l'attaque est entièrement esquivée.";
                g.addHistory(line);
                appendBreakdown(r, line);
            }

            // Invulnérabilité : annule les dégâts
            boolean preventedByInvuln = false;
            if (defFx != null && defFx.isInvulnerable() && dmg > 0) {
                preventedByInvuln = true;
                dmg = 0;
            }

            if (dmg > 0) {
                if (defPlayer != null) {
                    defPlayer.setHp(Math.max(0, defPlayer.getHp() - dmg));
                } else if (defMonster != null) {
                    defMonster.hp = Math.max(0, defMonster.hp - dmg);
                }

                // Effet Liche : Applique marque ténébreuse sur un hit
                if (attackerIsMonster
                        && attackerMonster.type == Game.MonsterType.LICHE
                        && defPlayer != null
                        && "HUNTER".equals(defPlayer.getRole())) {

                    if (g.getDarkMarkedHunters() == null) {
                        g.setDarkMarkedHunters(new java.util.HashSet<>());
                    }

                    if (!g.getDarkMarkedHunters().contains(defPlayer.getId())) {
                        g.getDarkMarkedHunters().add(defPlayer.getId());
                        g.addRaidMod(defPlayer.getId(), "MARKED", 0, "CORRUPTION:MARK:DSP");

                        String line = "Liche — " + g.nameOf(defPlayer.getId())
                                + " est frappé par la Liche et reçoit une Marque ténébreuse.";
                        g.addHistory(line);
                        appendBreakdown(r, line);
                    }
                }
            }

            // Si le vampire subit des dégâts "normaux" pendant la PHASE3
            if (defPlayer != null && dmg > 0 && "VAMPIRE".equals(defPlayer.getRole())) {
                g.setVampireTookDamageThisRaid(true);
            }

            // Eau bénite (mode ATTACK) : +4 dégâts sacrés sur le vampire
            if (dmg > 0
                    && atkPlayer != null
                    && defPlayer != null
                    && "HUNTER".equals(atkPlayer.getRole())
                    && "VAMPIRE".equals(defPlayer.getRole())
                    && atkFx != null
                    && atkFx.isHolyWaterAttack()) {

                int extra = 4;
                int beforeHp = defPlayer.getHp();
                defPlayer.setHp(Math.max(0, defPlayer.getHp() - extra));
                int realExtra = beforeHp - defPlayer.getHp();

                if (realExtra > 0) {
                    String line = "Eau bénite — " + g.nameOf(atkPlayer.getId())
                            + " inflige " + realExtra
                            + " dégâts sacrés supplémentaires au vampire.";
                    g.addHistory(line);
                    appendBreakdown(r, line);

                    // Ces dégâts comptent aussi comme "le vampire a pris des dégâts ce raid"
                    g.setVampireTookDamageThisRaid(true);
                }

                // Une seule utilisation
                atkFx.setHolyWaterAttack(false);
            }

            // Étourdissement par arme de chasseur (cible = vampire ou serviteur)
            if (atkPlayer != null
                    && defPlayer != null
                    && ("VAMPIRE".equals(defPlayer.getRole())
                            || "SERVANT".equals(defPlayer.getRole())
                            || "HUNTER".equals(defPlayer.getRole()))
                    && dmg > 0) {

                int penality = equipment.stunPenalityForWeapon(atkPlayer.getWeapon());
                if (penality > 0) {
                    // Mod ENGINE sur l'ATTACK de la cible
                    g.addRaidMod(defPlayer.getId(), "ATTACK", -penality, "HIT:STUN_WEAPON:ENG");

                    String line = g.nameOf(atkPlayer.getId())
                            + " étourdit " + g.nameOf(defPlayer.getId())
                            + " : -" + penality + " ATK à sa prochaine attaque.";
                    g.addHistory(line);
                    appendBreakdown(r, line);
                }
            }

            // Tenue à distance (armes à distance chasseur)
            if (atkPlayer != null
                    && defPlayer != null
                    && ("VAMPIRE".equals(defPlayer.getRole())
                            || "SERVANT".equals(defPlayer.getRole())
                            || "HUNTER".equals(defPlayer.getRole()))
                    && dmg > 0
                    && equipment.hunterWeaponIsRanged(atkPlayer.getWeapon())
                    && equipment.rangedKeepAwayTriggered(atkPlayer.getWeapon(), rawAtk)) {

                // Puce DISPLAY sur la cible : "tenu à distance"
                g.addRaidMod(defPlayer.getId(), "HIT", 0, "HIT:RANGED_WEAPON:DSP");

                String line = g.nameOf(defPlayer.getId())
                        + " est tenu à distance par " + g.nameOf(atkPlayer.getId())
                        + " : il ne pourra pas riposter ce raid contre lui.";
                g.addHistory(line);
                appendBreakdown(r, line);

                // On supprime de la file la riposte inverse (défenseur → attaquant)
                cancelReverseFight(g, r.getAttackerId(), r.getDefenderId());
            }

            // Sangsue : se soigne des dégâts infligés (après invulnérabilité)
            int healedByLeech = 0;
            if (atkPlayer != null && atkFx != null && atkFx.isLeech() && dmg > 0) {
                int beforeHp = atkPlayer.getHp();
                int maxHp = maxHpFor(g, atkPlayer);
                atkPlayer.setHp(Math.min(maxHp, atkPlayer.getHp() + dmg));
                healedByLeech = atkPlayer.getHp() - beforeHp;
            }

            // Régénération via arme vampirique (en plus ou à la place de la Potion sangsue)
            int healedByEquip = 0;
            if (atkPlayer != null
                    && ("VAMPIRE".equals(atkPlayer.getRole()) || "SERVANT".equals(atkPlayer.getRole()))
                    && dmg > 0
                    && r.getAttackerRoll() != null) {

                int regen = equipment.vampRegenAmount(atkPlayer.getWeapon(), r.getAttackerRoll());
                if (regen > 0) {
                    int beforeHp = atkPlayer.getHp();
                    int maxHp = maxHpFor(g, atkPlayer);
                    atkPlayer.setHp(Math.min(maxHp, atkPlayer.getHp() + regen));
                    healedByEquip = atkPlayer.getHp() - beforeHp;
                }
            }

            // -------------------
            // Vols de ressource par le vampire (jamais l'or ni l'argent)
            // - Vol de base : si dégâts > 0 sur un chasseur ET jet d'attaque au maximum du dé
            // - Salle de bal : Attaque sournoise = 1 vol supplémentaire
            // sur la cible, même si l’attaque échoue.
            // -------------------
            java.util.List<String> theftLines = new java.util.ArrayList<>();

            boolean vampVsHunter = atkPlayer != null && "VAMPIRE".equals(atkPlayer.getRole()) &&
                    defPlayer != null && "HUNTER".equals(defPlayer.getRole());

            String ballroomCode = Infra.BALLROOM.locationCode();
            boolean ballroomSneakActive = g.isBallroomSneakAttack()
                    && g.getBuiltInfras() != null
                    && g.getBuiltInfras().contains(Infra.BALLROOM)
                    && ballroomCode != null
                    && ballroomCode.equals(loc);

            // 1) Effet Salle de bal : Attaque sournoise
            // → 1 vol garanti sur le défenseur, même si dmg == 0
            if (vampVsHunter && ballroomSneakActive) {
                String line = vampStealOne(g, atkPlayer, defPlayer);
                if (line != null) {
                    theftLines.add("Salle de bal — Attaque sournoise : " + line);
                }
            }

            // 2) Vol de base : uniquement si l’attaque inflige des dégâts et dé max
            if (vampVsHunter && dmg > 0 && rawAtk == diceSides(atkPlayer.getAttackDice())) {
                String line = vampStealOne(g, atkPlayer, defPlayer);
                if (line != null)
                    theftLines.add(line);
            }

            List<String> atkBk = new ArrayList<>();
            List<String> defBk = new ArrayList<>();

            // Valse sanguinaire
            List<String> waltzLines = new ArrayList<>();
            if (r.getBallroomWaltzBest() != null
                    && r.getBallroomWaltzRolls() != null
                    && !r.getBallroomWaltzRolls().isEmpty()) {

                waltzLines.add("Salle de bal — Valse sanguinaire : "
                        + g.entityName(r.getAttackerId())
                        + " a lancé " + r.getBallroomWaltzRolls().size()
                        + " dés " + r.getBallroomWaltzRolls()
                        + " et utilisé le meilleur : " + r.getBallroomWaltzBest() + ".");
            }

            atkBk.addAll(waltzLines);

            // --- ALTAR : lignes de contexte pour la purification par combat ---
            String altarCode = Infra.ALTAR.locationCode();
            boolean isAltarFight = altarCode != null && altarCode.equals(loc);

            if (isAltarFight
                    && locationEffects.isAltarBuilt(g)
                    && locationEffects.isAltarCorrupted(g)
                    && atkPlayer != null
                    && defPlayer != null
                    && "HUNTER".equals(atkPlayer.getRole())
                    && "VAMPIRE".equals(defPlayer.getRole())
                    && !g.isAltarVampTookDamageThisRaid()
                    && !g.isAltarBiteOccurredThisRaid()) {

                // Si l'attaque inflige des dégâts au vampire, on ajoute la ligne "fait
                // vaciller..."
                if (dmg > 0) {
                    String line = "Autel — "
                            + g.nameOf(atkPlayer.getId())
                            + " fait vaciller le vampire:"
                            + " si aucun rituel de morsure ne réussit d'ici la fin du raid,"
                            + " l'autel sera lavé de sa corruption.";
                    g.addHistory(line); // reste dans l'historique global
                    atkBk.add(line); // ET dans le breakdown
                }
            }

            // --- Lignes supplémentaires pour les nouvelles potions ---
            if (atkFx != null && atkFx.isDoubleAttack()) {
                atkBk.add(g.nameOf(r.getAttackerId())
                        + " voit son score d'attaque doublé par la Potion de rage : "
                        + atkScoreBeforeMul + " → " + atkScore + ".");
            }
            if (defFx != null && defFx.isDoubleDefense()) {
                defBk.add(g.nameOf(r.getDefenderId())
                        + " voit son score de défense doublé par la Potion de résilience : "
                        + defScoreBeforeMul + " → " + defScore + ".");
            }
            if (atkFx != null && atkFx.isInvisible()) {
                atkBk.add(g.nameOf(r.getAttackerId())
                        + " est invisible : "
                        + g.nameOf(r.getDefenderId())
                        + " ne lance pas de dé de défense (0).");
            }
            if (preventedByInvuln && defPlayer != null) {
                defBk.add(g.nameOf(r.getDefenderId())
                        + " est protégé par une Potion d'invulnérabilité : les dégâts sont annulés.");
            }
            if (healedByLeech > 0 && atkPlayer != null) {
                atkBk.add(g.nameOf(r.getAttackerId())
                        + " récupère " + healedByLeech + " PV grâce à la Potion de sangsue.");
            }
            if (atkFx != null && atkFx.isRapid() && r.isRapidExtra()) {
                atkBk.add(
                        g.nameOf(r.getAttackerId())
                                + " attaque une deuxième fois par l'effet de la Potion de rapidité.");
            }

            if (healedByEquip > 0 && atkPlayer != null) {
                atkBk.add(g.nameOf(atkPlayer.getId())
                        + " récupère " + healedByEquip
                        + " PV grâce à son arme vampirique.");
            }

            // Historique détaillé
            for (String ln : atkBk)
                g.addHistory(ln);
            for (String ln : defBk)
                g.addHistory(ln);

            if (r.getBreakdownLines() == null)
                r.setBreakdownLines(new java.util.ArrayList<>());
            r.getBreakdownLines().addAll(atkBk);
            r.getBreakdownLines().addAll(defBk);
            r.getBreakdownLines().addAll(theftLines);

            String an = g.entityName(r.getAttackerId());
            String dn = g.entityName(r.getDefenderId());

            String resultLine;
            if (dmg > 0)
                resultLine = an + " inflige " + dmg + " dégâts à " + dn;
            else
                resultLine = dn + " pare l'attaque de " + an;

            // Historique (comme avant)
            g.addHistory(resultLine);

            // Breakdown (NOUVEAU) => la modale spectate affichera toujours la vérité
            // serveur
            appendBreakdown(r, resultLine);

            int bleed = 0;
            if (atkPlayer != null && dmg > 0) {
                bleed = equipment.bleedBonusForWeapon(atkPlayer.getWeapon());
            }
            if (bleed > 0) {
                g.getBleedDamageByTarget().merge(r.getDefenderId(), bleed, Integer::sum);

                // Puce DSP temporaire sur la cible : "saigne"
                g.addRaidMod(r.getDefenderId(), "HIT", 0, "HIT:BLEED_WEAPON:DSP");

                // entityName : le défenseur peut être un MONSTRE (nameOf renverrait
                // son UUID brut dans l'historique)
                String line = g.entityName(r.getDefenderId())
                        + " commence à saigner (" + bleed + " dégâts en fin de raid).";
                g.addHistory(line);
                appendBreakdown(r, line);
            }

            // Purify Altar
            var vamp = g.vampire().get();
            if (vamp.getId().equals(r.getDefenderId())) {
                locationEffects.onAltarVampireDamaged(g, r, dmg);
            }

            // Si un monstre tombe à 0 PV ou moins, on le laisse dans la liste
            if (defMonster != null && defMonster.hp <= 0) {
                defMonster.hp = 0; // par sécurité, on le borne à 0
            }

            if (g.getUnstableTargetByPlayer().containsKey(r.getAttackerId())
                    && java.util.Objects.equals(g.getUnstableTargetByPlayer().get(r.getAttackerId()),
                            r.getDefenderId())) {
                g.addHistory(g.nameOf(r.getAttackerId()) + " revient à lui ...");
            }

            ev.sendResolved = true;
            ev.dmg = dmg;
            ev.defId = r.getDefenderId();

            if (defPlayer != null) {
                ev.defenderHp = defPlayer.getHp();
            } else if (defMonster != null) {
                ev.defenderHp = defMonster.hp;
            } else {
                ev.defenderHp = 0;
            }

            // Vampire vs chasseur ?
            boolean vampDamagedHunter = vampVsHunter && dmg > 0;

            // 1) Effet Salle de bal : Danse macabre → NE dépend que des dégâts
            if (vampDamagedHunter) {

                if (g.isBallroomDeathDance()
                        && g.getBuiltInfras() != null
                        && g.getBuiltInfras().contains(Infra.BALLROOM)) {

                    if (ballroomCode != null && ballroomCode.equals(r.getLocation())) {

                        int before = defPlayer.getCorruption();
                        if (before < 3) {
                            defPlayer.setCorruption(before + 1);
                            g.addHistory("Salle de bal — Danse macabre : "
                                    + g.nameOf(defPlayer.getId())
                                    + " subit 1 point de corruption (niveau " + (before + 1) + ").");
                        }
                    }
                }
            }

            // 2) Tentative de morsure : dégâts OU Faim irrépressible
            boolean vampCanBiteHunter = vampVsHunter
                    && defPlayer != null
                    && defPlayer.getCorruption() < 3
                    && (dmg > 0 || g.isHungerAllowsBiteThisRaid())
                    && (!r.isCloneAttack() || r.isCanBite());

            if (vampCanBiteHunter) {
                Game.BiteAttempt b = new Game.BiteAttempt();
                b.setId(java.util.UUID.randomUUID().toString());
                b.setAttackerId(atkPlayer.getId());
                b.setTargetId(defPlayer.getId());
                b.setLocation(r.getLocation());
                g.setCurrentBite(b);

                ev.startBite = true;
                ev.biteAtt = atkPlayer.getId();
                ev.biteTgt = defPlayer.getId();
                ev.biteLoc = r.getLocation();
            }

            // 3) Pieu béni : marquer le duel comme devant déclencher l'action
            boolean canFlagBlessedStake = atkPlayer != null
                    && "HUNTER".equals(atkPlayer.getRole())
                    && atkPlayer.isBlessedStake()
                    && dmg > 0;

            if (canFlagBlessedStake) {
                r.setBlessedStakePending(true);

                String line = "Épieu béni — "
                        + g.nameOf(atkPlayer.getId())
                        + " prépare un coup sacré supplémentaire contre "
                        + g.entityName(r.getDefenderId()) + ".";
                g.addHistory(line);
                appendBreakdown(r, line);
            }

            r.setResolvedAtMillis(System.currentTimeMillis());
        }

        // --- Après les dégâts / corruption / etc. : gérer morts + fin de partie
        flow.handleDeathsAndVictory(g);

        // --- Payload breakdown : on envoie toujours l'état courant
        if (r.getBreakdownLines() != null && !r.getBreakdownLines().isEmpty()) {
            ev.breakdown = new java.util.ArrayList<>(r.getBreakdownLines());
        } else {
            ev.breakdown = java.util.List.of();
        }

        // --- Commit
        store.save(g);

        // --- Events APRÈS COMMIT (ordre garanti)
        store.afterCommit(() -> {
            if (ev.sendAtk)
                live.diceRolled(g, ev.roundId, ev.atkId, "ATTACK", ev.atkRoll, ev.breakdown);
            if (ev.sendDef)
                live.diceRolled(g, ev.roundId, ev.defId, "DEFENSE", ev.defRoll, ev.breakdown);
            if (ev.sendResolved)
                live.combatResolved(g, ev.roundId, ev.dmg, ev.defId, ev.defenderHp, ev.breakdown);
            if (ev.startBite)
                live.biteStarted(g, ev.biteAtt, ev.biteTgt, ev.biteLoc);

            live.phaseChanged(g);
        });

        return g;
    }

    private static boolean combatNotResolved(RoundFight r) {
        return r == null || r.getResolvedAtMillis() == null || r.getResolvedAtMillis() == 0L;
    }

    private static boolean biteResolved(Game.BiteAttempt b) {
        return b != null
                && b.getRoll() != null
                && b.getResolvedAtMillis() != null
                && b.getResolvedAtMillis() != 0L;
    }

    public Game combatContinue(String gameId, String userId) {
        class Ev {
            boolean biteResolved;
            boolean advanced;
            boolean trapResolved;

            String att, tgt, loc; // pour la morsure
            String trapMode, trapOwnerId, trapTargetId; // pour Filet/Fosse/Incendiaire/Épieu béni
        }

        Ev ev = tx.execute(status -> {
            Game g = store.loadForUpdate(gameId);
            if (g.getPhase() != Phase.PHASE3)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE3");

            Ev out = new Ev();

            // 0) GESTION DES ACTIONS DE RAID (NET / PIT / INCENDIAIRE / BLESSED_STAKE)
            // AVANT TOUT
            Game.Action ca = g.getCurrentAction();
            if (ca != null
                    && ca.getMode() != null
                    && ("NET".equals(ca.getMode())
                            || "PIT".equals(ca.getMode())
                            || "INCENDIAIRE".equals(ca.getMode())
                            || "BLESSED_STAKE".equals(ca.getMode()))) {

                // Tant que le jet n'est pas fait, on laisse la modale ouverte
                if (ca.getRoll() == null) {
                    store.save(g);
                    return out;
                }

                // L'action vient d'être résolue (roll != null) → on clôture cette action
                out.trapResolved = true;
                out.trapMode = ca.getMode();
                out.trapOwnerId = ca.getOwnerId();
                out.trapTargetId = ca.getTargetId(); // pour INCENDIAIRE = infra.name()

                // On "oublie" l'action courante
                g.setCurrentAction(null);

                // On tente de préparer l'action suivante (Filet/Fosse/Incendiaire)
                actionCards.prepareNextRaidAction(g);

                // S'il reste une autre action, on s'arrête là :
                // - le front affichera la nouvelle modale via currentAction
                // - on NE touche PAS encore aux morsures / combats
                if (g.getCurrentAction() != null) {
                    store.save(g);
                    return out;
                }

                // Sinon : plus d'action de raid -> on laisse continuer la logique (morsure /
                // combats)
            }

            // 1) S'il y a une morsure en cours :
            if (g.getCurrentBite() != null) {
                var b = g.getCurrentBite();
                if (!biteResolved(b)) {
                    store.save(g);
                    return out;
                }
                out.biteResolved = true;
                out.att = b.getAttackerId();
                out.tgt = b.getTargetId();
                out.loc = b.getLocation();
                g.setCurrentBite(null);

            } else {
                // 2) Sinon on est sur un duel : s’il n’est pas encore résolu
                var r = g.getCurrentCombat();

                // on laisse filer jusqu'à la partie "hasAnyRemainingFight" + auto-PHASE4 plus
                // bas.
                if (r != null) {
                    if (combatNotResolved(r)) {
                        store.save(g);
                        return out;
                    }

                    boolean modsChanged = consumeHitModsAfterFight(g, r);
                    // pour forcer live.phaseChanged => snapshot frais côté front
                    if (modsChanged)
                        out.advanced = true;

                    // déclencheur Pieu béni
                    if (r.isBlessedStakePending() && g.getCurrentAction() == null) {
                        Game.Action a = new Game.Action();
                        a.setMode("BLESSED_STAKE");
                        a.setOwnerId(r.getAttackerId()); // le chasseur qui avait l’effet
                        a.setLocation(r.getLocation());
                        a.setTargetId(r.getDefenderId()); // même cible que le duel
                        a.setRoll(null); // d4 pas encore lancé
                        a.setBreakdownLines(new java.util.ArrayList<>());
                        a.setResolvedAtMillis(null);

                        g.setCurrentAction(a);
                        r.setBlessedStakePending(false); // flag consommé

                        store.save(g);
                        return out;
                    }
                }
            }

            // 3) Avancer la file uniquement quand c’est safe (morsure close ou duel résolu)
            if (g.getCombatsQueue() != null) {
                Integer idx = g.getCurrentCombatIndex();

                if (idx == null) {
                    // fin de file : par sécurité, on ne re-sélectionne rien
                    // (et si jamais currentCombat traîne, on nettoie)
                    if (g.getCurrentCombat() != null) {
                        g.setCurrentCombat(null);
                        out.advanced = true;
                    }
                } else {
                    int size = g.getCombatsQueue().size();
                    int next = idx + 1;

                    RoundFight nextFight = null;

                    while (next < size) {
                        RoundFight candidate = g.getCombatsQueue().get(next);

                        // ignorer les combats déjà résolus
                        if (!combatNotResolved(candidate)) {
                            next++;
                            continue;
                        }

                        boolean atkAlive = g.isEntityAlive(candidate.getAttackerId());
                        boolean defAlive = g.isEntityAlive(candidate.getDefenderId());

                        if (atkAlive && defAlive) {
                            nextFight = candidate;
                            break; // on a trouvé le prochain duel valide
                        }

                        // sinon on skip ce combat et on regarde le suivant
                        next++;
                    }

                    if (nextFight != null) {
                        g.setCurrentCombatIndex(next);
                        g.setCurrentCombat(nextFight);

                        // *** copie locale "finale" pour les lambdas ***
                        final RoundFight nf = nextFight;

                        // --- Pré-remplissage pour Salle de bal / Valse sanguinaire ---
                        if (g.isBallroomBloodWaltz()
                                && g.getBuiltInfras() != null
                                && g.getBuiltInfras().contains(Infra.BALLROOM)) {

                            var attPlayer = g.getPlayers().stream()
                                    .filter(pp -> pp.getId().equals(nf.getAttackerId()))
                                    .findFirst()
                                    .orElse(null);
                            var defPlayer = g.getPlayers().stream()
                                    .filter(pp -> pp.getId().equals(nf.getDefenderId()))
                                    .findFirst()
                                    .orElse(null);

                            String ballroomCode = Infra.BALLROOM.locationCode();
                            boolean isVampAttackOnBallroom = attPlayer != null && "VAMPIRE".equals(attPlayer.getRole())
                                    && defPlayer != null && "HUNTER".equals(defPlayer.getRole())
                                    && ballroomCode != null
                                    && ballroomCode.equals(nf.getLocation());

                            if (isVampAttackOnBallroom && nf.getAttackerRoll() == null) {

                                // Est-ce que la Valse a déjà été tirée pour ce raid ?
                                boolean waltzAlreadyRolled = g.getBallroomBloodWaltzRolls() != null
                                        && !g.getBallroomBloodWaltzRolls().isEmpty()
                                        && g.getBallroomBloodWaltzBestRoll() != null;

                                if (waltzAlreadyRolled) {
                                    // Toujours copier les dés de Valse pour l'affichage UI
                                    nf.setBallroomWaltzRolls(
                                            new java.util.ArrayList<>(g.getBallroomBloodWaltzRolls()));
                                    nf.setBallroomWaltzBest(g.getBallroomBloodWaltzBestRoll());

                                    // Effets de raid de l'attaquant, pour savoir s'il a Focalisation
                                    RaidEffects atkFx = (g.getRaidEffects() != null && attPlayer != null)
                                            ? g.getRaidEffects().get(attPlayer.getId())
                                            : null;
                                    boolean attackerHasFocus = (atkFx != null && atkFx.isFocus());

                                    if (!attackerHasFocus) {
                                        // 🎯 Valse seule :
                                        // le jet d'attaque de ce duel est directement fixé par la Valse globale
                                        nf.setAttackerRoll(g.getBallroomBloodWaltzBestRoll());

                                    } else {
                                        // 🎯 Valse + Foca, mais sur un duel APRÈS le premier :
                                        // best de Valse = "dé de base" de ce duel (attackerFirstRoll)
                                        nf.setAttackerFirstRoll(g.getBallroomBloodWaltzBestRoll());
                                        // attackerRoll reste null → le prochain /roll sera la Foca directement
                                    }
                                }
                            }
                        }
                    } else {
                        // plus aucun combat valide
                        g.setCurrentCombatIndex(null);
                        g.setCurrentCombat(null);
                    }

                    out.advanced = true;
                }
            }

            // Recalcule s'il reste un combat réellement jouable
            boolean hasAnyRemainingFight = false;

            if (g.getCombatsQueue() != null) {
                for (RoundFight candidate : g.getCombatsQueue()) {

                    // 1) ignorer les combats déjà résolus
                    if (!combatNotResolved(candidate))
                        continue;

                    // 2) ignorer ceux dont un des deux est mort (skip logique)
                    boolean atkAlive = g.isEntityAlive(candidate.getAttackerId());
                    boolean defAlive = g.isEntityAlive(candidate.getDefenderId());
                    if (!atkAlive || !defAlive)
                        continue;

                    // 3) il reste vraiment un combat à jouer
                    hasAnyRemainingFight = true;
                    break;
                }
            }

            if (!hasAnyRemainingFight && g.getCurrentBite() == null && g.getCurrentCombat() == null) {
                construction.resolveInfraConstruction(g);
                flow.applyPhaseEntry(g, Phase.PHASE4);
                g.setCurrentAction(null);
                out.advanced = true; // pour envoyer un phaseChanged derrière
                store.save(g);
                return out;
            }

            store.save(g);
            return out;
        });

        Game gAfter = store.read(gameId);

        // Events après commit
        if (ev.biteResolved) {
            live.biteResolved(gAfter, ev.att, ev.tgt, ev.loc);
        }
        if (ev.trapResolved) {
            // C'est cet event que le front écoute (ACTION_RESOLVED)
            live.actionResolved(gAfter, ev.trapMode, ev.trapOwnerId, ev.trapTargetId);
        }
        if (ev.advanced) {
            // heartbeat pour faire faire un GET propre côté front (combats qui avancent /
            // fin de combats / passage phase4)
            live.phaseChanged(gAfter);
        }

        return gAfter;
    }

    private @Nullable String pickStealableFromHunter(Player h) {
        // Ressources volables chez un chasseur (ni or, ni argent)
        java.util.List<String> pool = new java.util.ArrayList<>();
        if (h.getWood() > 0)
            pool.add("wood");
        if (h.getHerbs() > 0)
            pool.add("herbs");
        if (h.getStone() > 0)
            pool.add("stone");
        if (h.getIron() > 0)
            pool.add("iron");
        if (h.getWater() > 0)
            pool.add("water");
        return pool.isEmpty() ? null : pool.get(dice.nextInt(pool.size()));
    }

    private @Nullable String vampStealOne(Game g, Player vamp, Player hunter) {
        String res = pickStealableFromHunter(hunter);
        if (res == null) {
            g.addHistory(g.nameOf(vamp.getId()) + " tente de voler, mais " + g.nameOf(hunter.getId())
                    + " n'a rien à prendre.");
            return null; // rien à afficher en breakdown
        }
        // retire au chasseur
        switch (res) {
            case "wood" -> hunter.setWood(hunter.getWood() - 1);
            case "herbs" -> hunter.setHerbs(hunter.getHerbs() - 1);
            case "stone" -> hunter.setStone(hunter.getStone() - 1);
            case "iron" -> hunter.setIron(hunter.getIron() - 1);
            case "water" -> hunter.setWater(hunter.getWater() - 1);
        }
        // donne au vampire
        vamp.grant(res, 1);

        String line = "Larcin — " + g.nameOf(vamp.getId()) + " vole 1 " + harvest.resLabelFr(res) + " à "
                + g.nameOf(hunter.getId()) + ".";
        g.addHistory(line);

        return line; // ← on renvoie la ligne pour la modale spectateur
    }

    // actions & potions
    private RaidEffects raidFx(Game g, String playerId) {
        return g.getRaidEffects().computeIfAbsent(playerId, __ -> new RaidEffects());
    }

    /**
     * Tous les joueurs concernés par un combat imminent :
     * - chasseurs impliqués
     * - vampire / serviteurs impliqués
     * - instables + leurs cibles
     * - chasseurs attaqués par des clones d'ombre
     */
    public Set<String> participantsOfUpcomingCombat(Game g) {
        Set<String> ids = new java.util.HashSet<>();

        if (g.getPlayers() == null || g.getPlayers().isEmpty()) {
            return ids;
        }

        // Instables affectés à la RÉCOLTE => ne combattent pas
        java.util.Set<String> unstableHarvesters = (g.getUnstableHarvestLocByPlayer() != null)
                ? g.getUnstableHarvestLocByPlayer().keySet()
                : java.util.Set.of();

        // Monstres vivants
        java.util.List<Game.Monster> aliveMonsters = (g.getMonsters() != null)
                ? g.getMonsters().stream()
                        .filter(m -> m.hp > 0)
                        .toList()
                : java.util.List.of();

        var groups = g.playersByLocation(); // loc -> List<Player>

        // --- 1) Combats "classiques" (vamp / serviteurs / monstres) ---
        for (var e : groups.entrySet()) {
            String loc = e.getKey();
            java.util.List<Player> onLoc = e.getValue();

            // chasseurs vivants non récolteurs
            var hunters = onLoc.stream()
                    .filter(p -> "HUNTER".equals(p.getRole())
                            && isAlive(p)
                            && !unstableHarvesters.contains(p.getId()))
                    .toList();

            // ennemis côté vampire (vamp + serviteurs)
            var enemies = onLoc.stream()
                    .filter(p -> isAlive(p) &&
                            ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())))
                    .toList();

            // monstre présent sur ce lieu ?
            boolean hasMonsterHere = aliveMonsters.stream()
                    .anyMatch(m -> loc.equals(m.location));

            boolean hasHunters = !hunters.isEmpty();
            boolean hasEnemyPlayers = !enemies.isEmpty();

            if (hasHunters && (hasEnemyPlayers || hasMonsterHere)) {
                // tous les chasseurs impliqués sur ce lieu
                hunters.forEach(p -> ids.add(p.getId()));
                // et les joueurs côté vampire présents (si il y en a)
                enemies.forEach(p -> ids.add(p.getId()));
            }
        }

        // --- 2) Duels instables explicites ---
        if (g.getUnstableTargetByPlayer() != null) {
            for (var entry : g.getUnstableTargetByPlayer().entrySet()) {
                String unstableId = entry.getKey();
                String targetId = entry.getValue();
                if (unstableId != null)
                    ids.add(unstableId);
                if (targetId != null)
                    ids.add(targetId);
            }
        }

        // --- 3) Clones des ombres : les chasseurs ciblés par un clone sont aussi
        // "participants" ---
        if (g.getClonesLocations() != null && !g.getClonesLocations().isEmpty()) {
            for (String loc : g.getClonesLocations()) {
                var onLoc = groups.get(loc);
                if (onLoc == null || onLoc.isEmpty())
                    continue;

                var hunters = onLoc.stream()
                        .filter(p -> "HUNTER".equals(p.getRole())
                                && isAlive(p)
                                && !unstableHarvesters.contains(p.getId()))
                        .toList();

                // On n'ajoute que les chasseurs : le vampire peut être ailleurs
                if (!hunters.isEmpty()) {
                    hunters.forEach(p -> ids.add(p.getId()));
                }
            }
        }

        return ids;
    }

    @Transactional
    public Game usePotion(String gameId, String playerId, Potion type) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        // Uniquement en PREPHASE3, s'il y a un combat imminent, et si je suis concerné
        if (g.getPhase() != Phase.PREPHASE3 || !g.isHasUpcomingCombat())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "potions usable only during PREPHASE3 before combat");

        Set<String> allowed = participantsOfUpcomingCombat(g);
        if (!allowed.contains(playerId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "you are not part of the upcoming combat");

        WeatherStatus ws1 = g.getWeatherStatus();
        WeatherStatus ws2 = g.getSecondaryWeatherStatus();
        // Cataclysme actif ⟺ une météo secondaire existe (base + secondaire = les 2
        // météos choisies). Un BLIZZARD actif gèle les potions de TOUT LE MONDE, sauf
        // le vampire lanceur quand ce BLIZZARD fait partie de son Cataclysme.
        boolean cataclysme = ws2 != null;
        boolean blizzardActive = ws1 == WeatherStatus.BLIZZARD || ws2 == WeatherStatus.BLIZZARD;
        boolean vampExempt = cataclysme
                && playerId.equals(g.vampire().map(Player::getId).orElse(null));
        if (blizzardActive && !vampExempt) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Impossible d'utiliser des potions sous le blizzard: elles sont gelées.");
        }

        // bloque les instables qui succombent à la corruption
        if (g.getPhase() == Phase.PREPHASE3 && corruption.hasSuccumbedToCorruption(g, playerId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu succombes à la corruption : tu ne peux pas utiliser tes potions ce raid.");
        }

        // --- inventaire sur Player
        Player p = g.getPlayers().stream()
                .filter(pp -> pp.getId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game"));

        List<String> potionInv = p.getPotions();
        List<String> elixirInv = p.getElixirs();

        boolean hasPotion = potionInv != null && potionInv.contains(type.name());
        boolean hasElixir = elixirInv != null && elixirInv.contains(type.name());

        if (!hasPotion && !hasElixir) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "item not in inventory");
        }

        if (!isAlive(p)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu es hors de combat pour le reste de la partie.");
        }

        // --- mutations + historique (PAS d'events ici)
        String feedText;

        switch (type) {
            case FORCE -> {
                if (g.getRaidMods() == null)
                    g.setRaidMods(new HashMap<>());
                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("ATTACK", +1, "POTION:FORCE"));
                decks.discardPotion(g, type.name());

                g.addHistory(g.nameOf(playerId) + " utilise une potion de force.");
                feedText = g.nameOf(playerId) + " boit une potion de force !";
            }
            case ENDURANCE -> {
                if (g.getRaidMods() == null)
                    g.setRaidMods(new HashMap<>());
                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("DEFENSE", +1, "POTION:ENDURANCE"));
                decks.discardPotion(g, type.name());

                g.addHistory(g.nameOf(playerId) + " utilise une potion d’endurance.");
                feedText = g.nameOf(playerId) + " boit une potion d’endurance !";
            }
            case VIE -> {
                int before = p.getHp();
                // Source unique du plafond de PV (vampire = 20 + 10 * nb chasseurs)
                int max = maxHpFor(g, p);

                int d6 = dice.roll(6);
                int amount = 2 + d6;

                p.setHp(Math.min(max, p.getHp() + amount));
                int healed = p.getHp() - before;

                decks.discardPotion(g, type.name());

                g.addHistory(g.nameOf(playerId)
                        + " utilise une potion de vie et régénère " + healed + " pv (2 + d6=" + d6 + ").");
                feedText = g.nameOf(playerId) + " boit une potion de vie !";
            }
            case FOCALISATION -> {
                var fx = raidFx(g, playerId);
                fx.setFocus(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("FOCALISATION", 0, "POTION:FOCALISATION:DSP"));
                decks.discardPotion(g, type.name());

                g.addHistory(g.nameOf(playerId)
                        + " utilise une potion de focalisation.");
                feedText = g.nameOf(playerId) + " boit une potion de focalisation !";
            }
            case SANGSUE -> {
                var fx = raidFx(g, playerId);
                fx.setLeech(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("SANGSUE", 0, "POTION:SANGSUE:DSP"));
                decks.discardPotion(g, type.name());

                g.addHistory(g.nameOf(playerId) +
                        " utilise une potion de sangsue.");
                feedText = g.nameOf(playerId) + " boit une potion de sangsue !";
            }
            case RAGE -> {
                if (p.isElixirUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as déjà utilisé un élixir ce raid.");
                }
                var fx = raidFx(g, playerId);
                fx.setDoubleAttack(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("RAGE", 0, "POTION:RAGE:DSP"));

                decks.discardElixir(g, type.name());
                p.setElixirUsedThisRaid(true);

                g.addHistory(g.nameOf(playerId)
                        + " utilise un elixir de rage.");
                feedText = g.nameOf(playerId) + " boit un elixir de rage !";
            }
            case RESILIENCE -> {
                if (p.isElixirUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as déjà utilisé un élixir ce raid.");
                }
                var fx = raidFx(g, playerId);
                fx.setDoubleDefense(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("RESILIENCE", 0, "POTION:RESILIENCE:DSP"));

                decks.discardElixir(g, type.name());
                p.setElixirUsedThisRaid(true);

                g.addHistory(g.nameOf(playerId)
                        + " utilise un elixir de résilience.");
                feedText = g.nameOf(playerId) + " boit un elixir de résilience !";
            }
            case RAPIDITE -> {
                if (p.isElixirUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as déjà utilisé un élixir ce raid.");
                }
                var fx = raidFx(g, playerId);
                fx.setRapid(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("RAPIDITE", 0, "POTION:RAPIDITE:DSP"));

                decks.discardElixir(g, type.name());
                p.setElixirUsedThisRaid(true);

                g.addHistory(g.nameOf(playerId)
                        + " utilise un elixir de rapidité.");
                feedText = g.nameOf(playerId) + " boit un elixir de rapidité !";
            }
            case INVISIBILITE -> {
                if (p.isElixirUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as déjà utilisé un élixir ce raid.");
                }
                var fx = raidFx(g, playerId);
                fx.setInvisible(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("INVISIBILITE", 0, "POTION:INVISIBILITE:DSP"));

                decks.discardElixir(g, type.name());
                p.setElixirUsedThisRaid(true);

                g.addHistory(g.nameOf(playerId)
                        + " utilise un elixir d’invisibilité.");
                feedText = g.nameOf(playerId) + " boit un elixir d’invisibilité !";
            }
            case INVULNERABILITE -> {
                if (p.isElixirUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as déjà utilisé un élixir ce raid.");
                }
                var fx = raidFx(g, playerId);
                fx.setInvulnerable(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("INVULNERABILITE", 0, "POTION:INVULNERABILITE:DSP"));

                decks.discardElixir(g, type.name());
                p.setElixirUsedThisRaid(true);

                g.addHistory(g.nameOf(playerId)
                        + " utilise un elixir d’invulnérabilité.");
                feedText = g.nameOf(playerId) + " boit un elixir d’invulnérabilité !";
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown potion");
        }

        // consommer l’item sur le Player
        if (hasPotion) {
            potionInv.remove(type.name());
        } else {
            elixirInv.remove(type.name());
        }

        // --- commit
        store.save(g);

        // --- events APRÈS COMMIT
        store.afterCommit(() -> {
            pushLive(g, feedText);
            live.potionUsed(g, playerId, type.name());
            live.raidModsUpdated(g);
        });

        return g;
    }

    private int maxHpFor(Game g, Player p) {
        if ("VAMPIRE".equals(p.getRole())) {
            return 20 + (g.getInitialPlayerCount() - 1) * 10;
        }
        return 20;
    }

    private boolean hasInvisibility(Game g, String playerId) {
        if (g.getRaidEffects() == null)
            return false;
        RaidEffects fx = g.getRaidEffects().get(playerId);
        return fx != null && fx.isInvisible();
    }

    private int rollMonsterAttack(Game.Monster m) {
        int sides = diceSides(m.attackDice); // même helper que pour les joueurs
        if (sides <= 0)
            return 0;
        return dice.roll(sides);
    }

    private int rollMonsterDefense(Game.Monster m) {
        int sides = diceSides(m.defenseDice);
        if (sides <= 0)
            return 0;
        return dice.roll(sides);
    }

    /**
     * Laboratoire : Alchimie simple (ALCHEMY).
     * - Le vampire gagne 1 potion commune gratuite.
     */
    public void applyBleed(Game g) {
        if (g.getBleedDamageByTarget() == null)
            return;
        for (var e : g.getBleedDamageByTarget().entrySet()) {
            String targetId = e.getKey();
            int bleed = e.getValue();
            if (bleed <= 0)
                continue;

            Player p = g.getPlayers().stream()
                    .filter(pl -> pl.getId().equals(targetId))
                    .findFirst().orElse(null);
            Game.Monster m = g.findMonster(targetId);

            if (p != null) {
                p.setHp(Math.max(0, p.getHp() - bleed));
                g.addHistory("Phase 4 — " + g.nameOf(p.getId())
                        + " subit " + bleed + " dégâts de saignement.");
            } else if (m != null && m.hp > 0) {
                m.hp = Math.max(0, m.hp - bleed);
                g.addHistory("Phase 4 — "
                        + m.type.labelFr() + " saigne encore et perd " + bleed + " PV.");
            }
            // suppression de la puce DSP temporaire
            var mods = g.getRaidMods().get(targetId);
            if (mods != null) {
                mods.removeIf(mm -> "HIT:BLEED_WEAPON:DSP".equals(mm.getSource()));
            }
        }
        g.getBleedDamageByTarget().clear();

        // --- Après les dégâts: gérer morts + fin de partie
        flow.handleDeathsAndVictory(g);
    }

    /**
     * Consomme les effets one-shot posés sur un HIT :
     * - HIT:STUN_WEAPON:ENG (malus d'attaque sur vampire/serviteur, posé sur la
     * cible)
     * - HIT:RANGED_WEAPON:DSP (puce "tenu à distance" sur la cible
     * vampire/serviteur)
     *
     * Appelé après la résolution d'un duel, juste avant de passer au combat
     * suivant.
     */
    public boolean consumeHitModsAfterFight(Game g, RoundFight r) {
        boolean changed = false;

        // 1) Étourdissement (sur l'attaquant vampire/serviteur)
        var atkPlayer = g.getPlayers().stream()
                .filter(p -> p.getId().equals(r.getAttackerId()))
                .findFirst()
                .orElse(null);

        if (atkPlayer != null
                && ("VAMPIRE".equals(atkPlayer.getRole()) || "SERVANT".equals(atkPlayer.getRole()))) {

            var mods = (g.getRaidMods() != null) ? g.getRaidMods().get(atkPlayer.getId()) : null;
            if (mods != null && !mods.isEmpty()) {
                boolean removed = mods.removeIf(m -> "HIT:STUN_WEAPON:ENG".equals(m.getSource()));
                if (removed) {
                    changed = true;
                    g.addHistory(g.nameOf(atkPlayer.getId()) + " se remet de l'étourdissement.");
                }
                if (mods.isEmpty())
                    g.getRaidMods().remove(atkPlayer.getId()); // optionnel mais propre
            }
        }

        // 2) Tenu à distance (sur le défenseur)
        var defMods = (g.getRaidMods() != null) ? g.getRaidMods().get(r.getDefenderId()) : null;
        if (defMods != null && !defMods.isEmpty()) {
            boolean removed = defMods.removeIf(m -> "HIT:RANGED_WEAPON:DSP".equals(m.getSource()));
            if (removed) {
                changed = true;
                g.addHistory(g.entityName(r.getDefenderId()) + " n'est plus tenu à distance.");
            }
            if (defMods.isEmpty())
                g.getRaidMods().remove(r.getDefenderId());
        }

        return changed;
    }

    public void purgeTransientRaidMods(Game g) {
        if (g.getRaidMods() == null)
            return;

        for (var e : g.getRaidMods().entrySet()) {
            var mods = e.getValue();
            if (mods == null)
                continue;
            mods.removeIf(m -> {
                String s = m.getSource();
                return "HIT:STUN_WEAPON:ENG".equals(s) || "HIT:RANGED_WEAPON:DSP".equals(s);
            });
        }
        g.getRaidMods().entrySet().removeIf(e -> e.getValue() == null || e.getValue().isEmpty());
    }

    /**
     * Supprime de la file des combats le duel "inverse"
     * (riposte) où attaquant = defId et défenseur = attId.
     */
    public void cancelReverseFight(Game g, String attId, String defId) {
        if (g.getCombatsQueue() == null || g.getCurrentCombatIndex() == null)
            return;

        int idx = g.getCurrentCombatIndex();
        var queue = g.getCombatsQueue();

        // On ne touche qu’aux combats APRÈS le duel courant
        for (int i = idx + 1; i < queue.size(); i++) {
            RoundFight rf = queue.get(i);
            if (defId.equals(rf.getAttackerId()) && attId.equals(rf.getDefenderId())) {
                queue.remove(i);
                break;
            }
        }
    }

    @Transactional
    /**
     * Annule tous les combats en attente impliquant un joueur qui vient de devenir
     * SERVANT. /**
     * Appelé après une transformation Hunter -> Servant pour nettoyer la queue de
     * combats.
     */
    public void cancelPendingCombatsForPlayer(Game g, Player player) {
        if (g.getCombatsQueue() == null || g.getCombatsQueue().isEmpty()) {
            return;
        }

        String playerId = player.getId();
        List<RoundFight> queue = g.getCombatsQueue();

        queue.removeIf(fight -> playerId.equals(fight.getAttackerId()) ||
                playerId.equals(fight.getDefenderId()));
    }
}
