package org.castello.game.domain;

import org.castello.game.Game;
import org.castello.game.Phase;
import org.castello.game.StatMod;
import org.castello.game.WeatherStatus;
import org.castello.game.support.Dice;
import org.castello.game.support.GameStore;
import org.castello.live.LiveEvents;
import org.castello.player.Player;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Domaine Météo.
 *
 * PHASE0 : le vampire lance un d12 -> un statut météo qui buffe/débuffe
 * chaque camp pour le raid (mods "WEATHER:*" dans raidMods). Jusqu'à trois
 * statuts peuvent se cumuler (carte Cataclysme). Le Cyclone (WIND) inflige
 * en plus des pertes de ressources de construction.
 */
@Service
public class WeatherService {

    private final GameStore store;
    private final Dice dice;
    private final LiveEvents live;

    public WeatherService(GameStore store, Dice dice, LiveEvents live) {
        this.store = store;
        this.dice = dice;
        this.live = live;
    }

    private WeatherStatus mapRollToWeather(int roll) {
        return switch (roll) {
            case 1 -> WeatherStatus.SUNNY;
            case 2 -> WeatherStatus.FOG;
            case 3 -> WeatherStatus.AURORA;
            case 4 -> WeatherStatus.CLOUDY;
            case 5 -> WeatherStatus.WIND;
            case 6 -> WeatherStatus.STORM;
            case 7 -> WeatherStatus.RAIN;
            case 8 -> WeatherStatus.BLIZZARD;
            case 9 -> WeatherStatus.DUSK;
            case 10 -> WeatherStatus.NIGHT_DARK;
            case 11 -> WeatherStatus.NIGHT_CLEAR;
            case 12 -> WeatherStatus.FULL_MOON;
            default -> null;
        };
    }

    public String weatherNameFr(WeatherStatus ws) {
        return switch (ws) {
            case SUNNY -> "Jour ensoleillé";
            case FOG -> "Brouillard protecteur";
            case AURORA -> "Aurore";
            case WIND -> "Cyclone";
            case CLOUDY -> "Ciel couvert";
            case STORM -> "Orage";
            case RAIN -> "Pluie diluvienne";
            case BLIZZARD -> "Blizzard";
            case DUSK -> "Crépuscule";
            case NIGHT_DARK -> "Nuit obscure";
            case NIGHT_CLEAR -> "Nuit claire";
            case FULL_MOON -> "Pleine lune";
            case BLOOD_MOON -> "Lune sanglante";
        };
    }

    public String weatherDescFr(WeatherStatus ws) {
        return switch (ws) {
            case SUNNY -> "La lumière domine.\n+1 attaque pour les chasseurs et –1 défense pour le vampire.";
            case FOG -> "La brume étouffe les sons et couvre l'approche.\n+1 attaque des chasseurs.";
            case AURORA -> "La lumière progresse.\n-1 défense pour le vampire.";
            case WIND -> "Un cyclone dévaste la région.\n" +
                    "Le vampire ne peut construire ce raid.\n" +
                    "Chaque chasseur perd 1 ressource de construction aléatoire.\n" +
                    "Le domaine perd ensuite 1 ressource de construction aléatoire par construction.";
            case CLOUDY -> "Lumière terne.\n-1 attaque pour le vampire.";
            case STORM -> "La foudre déstabilise au combat.\n-2 défense pour tous.";
            case RAIN -> "La pluie torrentielle alourdit chaque geste.\n-2 attaque pour tous.";
            case BLIZZARD -> "Froid mordant.\nPotions gelées et -1 attaque pour tous.";
            case DUSK -> "Les ombres progressent.\n+1 défense du vampire.";
            case NIGHT_DARK ->
                "Les ombres dominent.\n+1 attaque du vampire. Les chasseurs ne peuvent utiliser de pièges.";
            case NIGHT_CLEAR ->
                "La lune éclaire légèrement et le vampire gagne en puissance.\n+1 attaque du vampire et –1 défense pour les chasseurs.";
            case FULL_MOON -> "La pleine lune exalte le sang ancien.\n+2 attaque du vampire.";
            case BLOOD_MOON -> "La Pleine lune devient Lune sanglante.\n+4 Attaque du vampire.";
        };
    }

    public void rebuildWeatherMods(Game g) {
        // Sécurité : map toujours présente
        if (g.getRaidMods() == null)
            g.setRaidMods(new java.util.HashMap<>());

        // 1) retirer tous les effets météo précédents
        for (var entry : g.getRaidMods().entrySet()) {
            var list = entry.getValue();
            if (list == null)
                continue;
            list.removeIf(m -> m.getSource() != null && m.getSource().startsWith("WEATHER:"));
        }

        WeatherStatus ws1 = g.getWeatherStatus(); // base
        WeatherStatus ws2 = g.getSecondaryWeatherStatus(); // extra 2
        WeatherStatus ws3 = g.getThirdWeatherStatus(); // extra 3

        // 2) si pas de météo active => on s’arrête
        if (ws1 == null && ws2 == null && ws3 == null)
            return;

        // 3) s'assurer qu'il y a une liste pour chaque joueur
        for (var p : g.getPlayers()) {
            g.getRaidMods().computeIfAbsent(p.getId(), __ -> new java.util.ArrayList<>());
        }

        // 4) Appliquer les effets d'UN, DEUX ou TROIS statuts
        if (ws1 != null) {
            applyWeatherStatusMods(g, ws1);
        }
        if (ws2 != null) {
            applyWeatherStatusMods(g, ws2);
        }
        if (ws3 != null) {
            applyWeatherStatusMods(g, ws3);
        }
    }

    private void applyWeatherStatusMods(Game g, WeatherStatus ws) {
        switch (ws) {
            case SUNNY -> {
                for (var p : g.getPlayers()) {
                    if ("HUNTER".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +1, "WEATHER:SUNNY"));
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", -1, "WEATHER:SUNNY"));
                }
            }
            case FOG -> {
                for (var p : g.getPlayers())
                    if ("HUNTER".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +1, "WEATHER:FOG"));
            }
            case AURORA -> {
                for (var p : g.getPlayers())
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", -1, "WEATHER:AURORA"));
            }
            case CLOUDY -> {
                for (var p : g.getPlayers()) {
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", -1, "WEATHER:CLOUDY"));
                }
            }
            case WIND -> {
                for (var p : g.getPlayers()) {
                    if ("HUNTER".equals(p.getRole())) {
                        g.getRaidMods().get(p.getId())
                                .add(new StatMod("CONSTRUCTION", 0, "WEATHER:WIND:HUNTER:DSP"));
                    } else if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                        g.getRaidMods().get(p.getId())
                                .add(new StatMod("CONSTRUCTION", 0, "WEATHER:WIND:VAMP:DSP"));
                    }
                }
            }
            case STORM -> {
                for (var p : g.getPlayers())
                    g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", -2, "WEATHER:STORM"));
            }
            case RAIN -> {
                for (var p : g.getPlayers())
                    g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", -2, "WEATHER:RAIN"));
            }
            case BLIZZARD -> {
                for (var p : g.getPlayers())
                    g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", -1, "WEATHER:BLIZZARD"));
            }
            case DUSK -> {
                for (var p : g.getPlayers())
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", +1, "WEATHER:DUSK"));
            }
            case NIGHT_DARK -> {
                for (var p : g.getPlayers())
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +1, "WEATHER:NIGHT_DARK"));
            }
            case NIGHT_CLEAR -> {
                for (var p : g.getPlayers()) {
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +1, "WEATHER:NIGHT_CLEAR"));
                    if ("HUNTER".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", -1, "WEATHER:NIGHT_CLEAR"));
                }
            }
            case FULL_MOON -> {
                for (var p : g.getPlayers())
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +2, "WEATHER:FULL_MOON"));
            }
            case BLOOD_MOON -> {
                for (var p : g.getPlayers()) {
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                        g.getRaidMods().get(p.getId())
                                .add(new StatMod("ATTACK", +4, "WEATHER:BLOOD_MOON"));
                    }
                }
            }
        }
    }

    @Transactional
    public Game rollWeather(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);

        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing user id");
        if (g.getPhase() != Phase.PHASE0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in weather phase");

        var vamp = g.vampire().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "no vampire"));
        if (!vamp.getId().equals(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can roll weather");

        if (g.getWeatherRoll() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "weather already rolled");

        // Sécurité : structures non-null
        if (g.getRaidMods() == null)
            g.setRaidMods(new HashMap<>());

        // 1) Mutations PURES (aucun save / aucun event ici)
        int roll = dice.roll(12);
        applyWeatherRoll(g, roll);

        // 2) Commit
        store.save(g);

        // 3) Events APRÈS COMMIT (aucune course avec les GET)
        store.afterCommit(() -> {
            live.weatherRolled(g); // payload construit depuis g (déjà commité)
            live.raidModsUpdated(g); // pour rafraîchir les puces météo côté UI
        });

        return g;
    }

    private void applyWeatherRoll(@NonNull Game g, int roll) {
        g.setWeatherRoll(roll);

        WeatherStatus ws = mapRollToWeather(roll);
        g.setWeatherStatus(ws);

        g.setWeatherStatusNameFr(weatherNameFr(ws));
        g.setWeatherDescriptionFr(weatherDescFr(ws));

        // Recalcule les mods météo (affichage/combat)
        rebuildWeatherMods(g);

        if (ws == WeatherStatus.WIND) {
            applyWindRepairs(g);
        }

        // Historique
        g.addHistory("Météo — " + g.getWeatherStatusNameFr());
        if (g.getWeatherDescriptionFr() != null && !g.getWeatherDescriptionFr().isBlank()) {
            g.addHistory(g.getWeatherDescriptionFr());
        }

        // Messages au centre (utilisés par le front)
        List<String> msgs = new ArrayList<>();
        msgs.add("Météo — " + (g.getWeatherStatusNameFr() != null ? g.getWeatherStatusNameFr() : ""));
        if (g.getWeatherDescriptionFr() != null && !g.getWeatherDescriptionFr().isBlank()) {
            msgs.add(g.getWeatherDescriptionFr());
        }
        g.setMessages(msgs);
    }

    public void applyWindRepairs(@NonNull Game g) {
        // 1) Chasseurs : -1 ressource aléatoire chacun (réparations du village)
        for (Player p : g.getPlayers()) {
            if (!"HUNTER".equals(p.getRole()))
                continue;

            String type = loseOneRandomBuildResource(p);
            if (type == null)
                continue;

            String who = g.nameOf(p.getId());
            String resFr = switch (type) {
                case "WOOD" -> "bois";
                case "IRON" -> "fer";
                case "STONE" -> "pierre";
                default -> "ressource";
            };

            g.addHistory(
                    "Cyclone — " + who
                            + " perd 1 " + resFr
                            + " pour réparer le village.");
        }

        // 2) Domaine : -1 ressource aléatoire par construction
        int nbInfras = countDomainConstructions(g); // à adapter à ta structure d'infras
        if (nbInfras <= 0) {
            return;
        }

        java.util.List<Player> vampSide = g.getPlayers().stream()
                .filter(p -> "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                .toList();

        if (vampSide.isEmpty()) {
            return;
        }

        for (int i = 0; i < nbInfras; i++) {
            Player payer = pickRandomWithBuildResources(vampSide);
            if (payer == null)
                break; // plus personne n’a de ressources de construction

            String type = loseOneRandomBuildResource(payer);
            if (type == null)
                break;

            String who = g.nameOf(payer.getId());
            String resFr = switch (type) {
                case "WOOD" -> "bois";
                case "IRON" -> "fer";
                case "STONE" -> "pierre";
                default -> "ressource";
            };

            g.addHistory(
                    "Cyclone — " + who
                            + " dépense 1 " + resFr
                            + " pour réparer le domaine.");
        }
    }

    /**
     * Retire 1 ressource de construction aléatoire (bois / fer, pierre plus tard).
     * 
     * @return "WOOD" | "IRON" | "STONE" | null si aucune ressource à retirer
     */
    private String loseOneRandomBuildResource(Player p) {
        java.util.List<String> pool = new java.util.ArrayList<>();

        if (p.getWood() > 0)
            pool.add("WOOD");
        if (p.getIron() > 0)
            pool.add("IRON");
        if (p.getStone() > 0)
            pool.add("STONE");

        if (pool.isEmpty())
            return null;

        String type = pool.get(dice.nextInt(pool.size()));
        switch (type) {
            case "WOOD" -> p.setWood(p.getWood() - 1);
            case "IRON" -> p.setIron(p.getIron() - 1);
            case "STONE" -> p.setStone(p.getStone() - 1);
        }

        return type;
    }

    /**
     * Choisit un joueur vamp-side ayant au moins 1 ressource de construction.
     */
    private Player pickRandomWithBuildResources(java.util.List<Player> players) {
        java.util.List<Player> candidates = players.stream()
                .filter(p -> p.getWood() > 0 || p.getIron() > 0 || p.getStone() > 0)
                .toList();
        if (candidates.isEmpty())
            return null;
        return candidates.get(dice.nextInt(candidates.size()));
    }

    /**
     * Nombre de constructions du domaine.
     */
    private int countDomainConstructions(Game g) {
        if (g.getBuiltInfras() == null)
            return 0;
        return g.getBuiltInfras().size();
    }

}
