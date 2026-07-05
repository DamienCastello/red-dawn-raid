package org.castello.game.domain;

import org.castello.game.Game;
import org.castello.game.Phase;
import org.castello.game.support.GameStore;
import org.castello.live.LiveEvents;
import org.castello.player.Player;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Domaine Échanges (PHASE4).
 *
 * Deux alliés du même camp (chasseur-chasseur, ou vampire-serviteur)
 * proposent chacun une offre de ressources ; l'échange n'a lieu que si
 * les deux confirment. Un statut REFUSED/CANCELLED clôt l'échange.
 */
@Service
public class TradeService {

    private final GameStore store;
    private final LiveEvents live;

    public TradeService(GameStore store, LiveEvents live) {
        this.store = store;
        this.live = live;
    }

    private boolean sameSideCanTrade(Player a, Player b) {
        if ("HUNTER".equals(a.getRole()) && "HUNTER".equals(b.getRole()))
            return true;
        // vamp side: vamp <-> servant uniquement
        if ("VAMPIRE".equals(a.getRole()) && "SERVANT".equals(b.getRole()))
            return true;
        if ("SERVANT".equals(a.getRole()) && "VAMPIRE".equals(b.getRole()))
            return true;
        return false;
    }

    private String sideOf(Player a, Player b) {
        return "HUNTER".equals(a.getRole()) && "HUNTER".equals(b.getRole()) ? "HUNTERS" : "VAMP_SIDE";
    }

    private Game.Trade getOrCreateTrade(Game g, String aId, String bId) {
        String lo = aId.compareTo(bId) <= 0 ? aId : bId;
        String hi = aId.compareTo(bId) <= 0 ? bId : aId;

        for (var t : g.getTrades()) {
            if ((t.getAId().equals(lo) && t.getBId().equals(hi)))
                return t;
        }
        var t = new Game.Trade();
        t.setId(java.util.UUID.randomUUID().toString());
        t.setAId(lo);
        t.setBId(hi);
        var pa = g.findPlayer(lo);
        var pb = g.findPlayer(hi);
        t.setSide(sideOf(pa, pb));
        g.getTrades().add(t);
        return t;
    }

    @Transactional
    public Game tradeSetMyOffer(String gameId, String userId, String targetId, Map<String, Integer> offer) {
        Game g = store.loadForUpdate(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var me = g.findPlayer(userId);
        var you = g.findPlayer(targetId);
        if (me == null || you == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "player not found");
        if (!sameSideCanTrade(me, you))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "pair not eligible");

        var t = getOrCreateTrade(g, userId, targetId);

        // sanitize
        Map<String, Integer> sanitized = new java.util.HashMap<>();
        if (offer != null) {
            for (var e : offer.entrySet()) {
                int q = Math.max(0, e.getValue() == null ? 0 : e.getValue());
                if (q > 0)
                    sanitized.put(e.getKey(), q);
            }
        }

        boolean iAmA = userId.equals(t.getAId());
        if (iAmA)
            t.setOfferA(sanitized);
        else
            t.setOfferB(sanitized);

        // UX : toute modif remet les deux côtés à PENDING
        t.setStatusA("PENDING");
        t.setStatusB("PENDING");
        t.setUpdatedAt(System.currentTimeMillis());

        store.save(g);
        store.afterCommit(() -> live.tradeSync(g, t));
        return g;
    }

    @Transactional
    public Game tradeAction(String gameId, String userId, String targetId, String action) {
        Game g = store.loadForUpdate(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var me = g.findPlayer(userId);
        var you = g.findPlayer(targetId);
        if (me == null || you == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "player not found");
        if (!sameSideCanTrade(me, you))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "pair not eligible");

        var t = getOrCreateTrade(g, userId, targetId);
        boolean iAmA = userId.equals(t.getAId());

        String st = switch (action) {
            case "confirm" -> "CONFIRMED";
            case "refuse" -> "REFUSED";
            case "cancel" -> "CANCELLED";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid action");
        };

        if (iAmA)
            t.setStatusA(st);
        else
            t.setStatusB(st);
        t.setUpdatedAt(System.currentTimeMillis());

        String tId = t.getId();
        String aId = t.getAId();
        String bId = t.getBId();
        Map<String, Integer> offerA = t.getOfferA() == null ? java.util.Map.of()
                : new java.util.HashMap<>(t.getOfferA());
        Map<String, Integer> offerB = t.getOfferB() == null ? java.util.Map.of()
                : new java.util.HashMap<>(t.getOfferB());

        boolean deleted = false;
        boolean success = false;

        if ("CONFIRMED".equals(t.getStatusA()) && "CONFIRMED".equals(t.getStatusB())) {
            success = true;
            applyTradeExchange(g, t);
            g.getTrades().remove(t);
            deleted = true;
        } else if (isFinal(t.getStatusA()) && isFinal(t.getStatusB())) {
            g.getTrades().remove(t);
            deleted = true;
        }

        store.save(g);
        if (deleted) {
            final boolean fSuccess = success;
            final var fOfferA = offerA;
            final var fOfferB = offerB;

            final String fResult = fSuccess ? "SUCCESS" : "CLOSED";
            final java.util.Map<String, Object> extra = fSuccess
                    ? new java.util.HashMap<>(java.util.Map.of(
                            "offerA", fOfferA,
                            "offerB", fOfferB))
                    : java.util.Map.of();

            store.afterCommit(() -> {
                live.tradeDeleted(
                        g, tId, aId, bId,
                        "FINAL",
                        fResult,
                        extra);
            });
        } else {
            store.afterCommit(() -> live.tradeSync(g, t));
        }
        return g;
    }

    public boolean isFinal(String s) {
        return "REFUSED".equals(s) || "CANCELLED".equals(s);
    }

    // Exécution de l'échange (débits puis crédits)
    private void applyTradeExchange(Game g, Game.Trade t) {
        var a = g.findPlayer(t.getAId());
        var b = g.findPlayer(t.getBId());
        if (a == null || b == null)
            return;

        // vérif stocks côté A et B
        if (!hasAll(a, t.getOfferA()) || !hasAll(b, t.getOfferB()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "insufficient resources");

        debit(a, t.getOfferA());
        debit(b, t.getOfferB());
        credit(a, t.getOfferB());
        credit(b, t.getOfferA());

        g.addHistory(g.nameOf(a.getId()) + " et " + g.nameOf(b.getId()) + " concluent un échange.");
    }

    // helpers
    private boolean hasAll(Player p, Map<String, Integer> pack) {
        if (pack == null)
            return true;
        for (var e : pack.entrySet()) {
            int need = Math.max(0, e.getValue() == null ? 0 : e.getValue());
            switch (e.getKey()) {
                case "wood" -> {
                    if (p.getWood() < need)
                        return false;
                }
                case "herbs" -> {
                    if (p.getHerbs() < need)
                        return false;
                }
                case "stone" -> {
                    if (p.getStone() < need)
                        return false;
                }
                case "iron" -> {
                    if (p.getIron() < need)
                        return false;
                }
                case "water" -> {
                    if (p.getWater() < need)
                        return false;
                }
                case "gold" -> {
                    if (p.getGold() < need)
                        return false;
                } // boutique dit or permis côté hunters
                case "souls" -> {
                    if (p.getSouls() < need)
                        return false;
                } // transmutation côté vamp
                case "silver" -> {
                    if (p.getSilver() < need)
                        return false;
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid resource: " + e.getKey());
            }
        }
        return true;
    }

    private void debit(Player p, Map<String, Integer> pack) {
        if (pack == null)
            return;
        for (var e : pack.entrySet()) {
            int q = Math.max(0, e.getValue() == null ? 0 : e.getValue());
            switch (e.getKey()) {
                case "wood" -> p.setWood(p.getWood() - q);
                case "herbs" -> p.setHerbs(p.getHerbs() - q);
                case "stone" -> p.setStone(p.getStone() - q);
                case "iron" -> p.setIron(p.getIron() - q);
                case "water" -> p.setWater(p.getWater() - q);
                case "gold" -> p.setGold(p.getGold() - q);
                case "souls" -> p.setSouls(p.getSouls() - q);
                case "silver" -> p.setSilver(p.getSilver() - q);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid resource: " + e.getKey());
            }
        }
    }

    private void credit(Player p, Map<String, Integer> pack) {
        if (pack == null)
            return;
        for (var e : pack.entrySet()) {
            int q = Math.max(0, e.getValue() == null ? 0 : e.getValue());
            switch (e.getKey()) {
                case "wood" -> p.setWood(p.getWood() + q);
                case "herbs" -> p.setHerbs(p.getHerbs() + q);
                case "stone" -> p.setStone(p.getStone() + q);
                case "iron" -> p.setIron(p.getIron() + q);
                case "water" -> p.setWater(p.getWater() + q);
                case "gold" -> p.setGold(p.getGold() + q);
                case "souls" -> p.setSouls(p.getSouls() + q);
                case "silver" -> p.setSilver(p.getSilver() + q);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid resource: " + e.getKey());
            }
        }
    }

}
