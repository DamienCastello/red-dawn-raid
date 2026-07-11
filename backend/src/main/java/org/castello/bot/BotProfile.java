package org.castello.bot;

import org.castello.game.Infra;

import java.util.List;

/**
 * Profil (archétype) d'un bot — voir docs/BOT-DESIGN.md §3.
 *
 * Décision produit : l'archétype n'est pas sélectionnable et vaut par défaut
 * ÉCONOMIQUE — Bâtisseur côté vampire, Prudent côté chasseur. Les autres
 * archétypes (Corrupteur, Prédateur, Traqueur…) et les PIVOTS de situation
 * qui modulent le comportement en cours de partie arrivent à l'étape 3 :
 * cette classe est le point d'ancrage prévu pour ça.
 */
public final class BotProfile {

    public enum Archetype {
        BUILDER, // vampire économique : Scierie → Mine → …
        PRUDENT // chasseur économique : éco / banque / équipement
    }

    private final Archetype archetype;

    private BotProfile(Archetype archetype) {
        this.archetype = archetype;
    }

    /** Preset économique selon le rôle (le seul disponible à l'étape 2). */
    public static BotProfile forRole(String role) {
        return "VAMPIRE".equals(role)
                ? new BotProfile(Archetype.BUILDER)
                : new BotProfile(Archetype.PRUDENT);
    }

    public Archetype archetype() {
        return archetype;
    }

    /**
     * Ordre de construction du vampire Bâtisseur : le socle économique
     * (revenus auto + récoltes améliorées) avant les bâtiments de contrôle.
     * Le bot suit cet ordre tant que les ressources suivent, sinon décale.
     */
    public List<Infra> constructionOrder() {
        return List.of(Infra.SAWMILL, Infra.MINE, Infra.LIBRARY, Infra.FORGE);
    }
}
