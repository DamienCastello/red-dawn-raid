package org.castello.game.domain;

import org.castello.game.Game;
import org.castello.game.GameStatus;
import org.castello.game.Phase;
import org.castello.game.support.Dice;
import org.castello.game.support.GameStore;
import org.castello.live.LiveEvents;
import org.castello.player.Player;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Domaine Banque (chasseurs).
 *
 * En PHASE4, les chasseurs déposent des pierres pour monter la banque de
 * niveau (10/15/20 pierres). À chaque entrée en PHASE4, la banque verse
 * un revenu à chaque chasseur vivant selon son niveau.
 */
@Service
public class BankService {

    private final GameStore store;
    private final Dice dice;
    private final LiveEvents live;

    public BankService(GameStore store, Dice dice, LiveEvents live) {
        this.store = store;
        this.dice = dice;
        this.live = live;
    }

    @Transactional
    public void contributeBankStone(String gameId, String playerId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "bank only in PHASE4");

        Player p = g.findPlayer(playerId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid player");

        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");

        if (p.getHp() <= 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dead player");

        int lvl = (g.getBankLevel() == null ? 0 : g.getBankLevel());
        int prog = (g.getBankStoneProgress() == null ? 0 : g.getBankStoneProgress());

        if (lvl >= 3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "bank already max");

        if (p.getStone() <= 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not enough stone");

        // 1) payer 1 pierre
        p.setStone(p.getStone() - 1);

        // 2) progresser
        prog += 1;

        // 3) coût requis selon niveau actuel -> prochain niveau
        int required = switch (lvl + 1) {
            case 1 -> 10;
            case 2 -> 15;
            case 3 -> 20;
            default -> Integer.MAX_VALUE;
        };

        boolean leveledUp = false;
        if (prog >= required) {
            lvl += 1;
            prog = 0; // reset pour le prochain palier
            leveledUp = true;

            g.addHistory("Banque — amélioration réussie : la banque passe au niveau " + lvl + ".");
        } else {
            g.addHistory("Banque — dépôt d’1 pierre (" + prog + "/" + required + ").");
        }

        g.setBankLevel(lvl);
        g.setBankStoneProgress(prog);

        store.save(g);

        store.afterCommit(() -> {
            Game fresh = store.read(gameId);
            live.bankUpdated(fresh, playerId); // WS (refresh front)
        });
    }

    public void applyBankBonusOnPhase4Entry(Game g) {
        int lvl = (g.getBankLevel() == null ? 0 : g.getBankLevel());
        if (lvl <= 0)
            return;

        for (Player p : g.getPlayers()) {
            if (!"HUNTER".equals(p.getRole()))
                continue;
            if (p.getHp() <= 0)
                continue;

            int goldGain = (lvl >= 3) ? 100 : 50;
            p.setGold(p.getGold() + goldGain);

            if (lvl >= 2) {
                int r = dice.nextInt(4); // 0..3
                switch (r) {
                    case 0 -> p.setWood(p.getWood() + 1);
                    case 1 -> p.setIron(p.getIron() + 1);
                    case 2 -> p.setHerbs(p.getHerbs() + 1);
                    case 3 -> p.setWater(p.getWater() + 1);
                }
            }
        }

        g.addHistory("Banque — bonus appliqué (niveau " + lvl + ") au début de la phase 4.");
    }
}
