package org.castello.game.domain;

import org.castello.game.Game;
import org.castello.player.Player;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.util.HashMap;

/**
 * Domaine Corruption (morsures, instables, marque ténébreuse).
 *
 * Échelle 0..3 : sain, affaibli (-1 ATK/DEF), instable (d6 en préphase,
 * 1-3 = sous contrôle du vampire), serviteur (change de camp).
 */
@Service
public class CorruptionService {

    public void rebuildCorruptionMods(@NonNull Game g) {
        if (g.getRaidMods() == null)
            g.setRaidMods(new HashMap<>());

        // Purge des anciennes entrées de corruption
        for (var list : g.getRaidMods().values()) {
            if (list != null) {
                list.removeIf(m -> {
                    String s = m.getSource();
                    return s != null && s.startsWith("CORRUPTION:");
                });
            }
        }

        // Réinjection selon le niveau
        for (var p : g.getPlayers()) {
            int lvl = p.getCorruption();

            if (lvl == 1) {
                // Effet moteur L1 : −1 ATK / −1 DEF
                g.addRaidMod(p.getId(), "ATTACK", -1, "CORRUPTION:L1:ENG");
                g.addRaidMod(p.getId(), "DEFENSE", -1, "CORRUPTION:L1:ENG");
                // Une seule puce d’affichage
                g.addRaidMod(p.getId(), "MULTIPLE", 0, "CORRUPTION:L1:DSP");
            } else if (lvl == 2) {
                // L2 : pas de debuff chiffré — seulement la puce “instable”
                g.addRaidMod(p.getId(), "INSTABLE", 0, "CORRUPTION:L2:DSP");
            } else if (lvl == 3) {
                // L3 : pas de debuff chiffré — seulement la puce “serviteur”
                g.addRaidMod(p.getId(), "SERVITEUR", 0, "CORRUPTION:L3:DSP");
            }
        }
        // --- Marque ténébreuse : puce DSP permanente tant que le chasseur est marqué
        // ---
        if (g.getDarkMarkedHunters() != null && !g.getDarkMarkedHunters().isEmpty()) {
            for (String pid : g.getDarkMarkedHunters()) {
                // sécurité : s'assurer que le joueur existe
                Player h = g.findPlayer(pid);
                if (h == null)
                    continue;

                g.addRaidMod(pid, "MARKED", 0, "CORRUPTION:MARK:DSP");
            }
        }
    }
}
