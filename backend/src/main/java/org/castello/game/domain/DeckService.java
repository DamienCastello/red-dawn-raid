package org.castello.game.domain;

import org.castello.game.Game;
import org.castello.game.support.Dice;
import org.castello.player.Player;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pioches et défausses du jeu (potions, élixirs, actions chasseur/vampire).
 *
 * Convention des decks ordonnés : le HAUT du deck est la FIN de la liste.
 * Quand un deck se vide, la défausse est remélangée automatiquement pour
 * reformer le deck.
 */
@Service
public class DeckService {

    private final Dice dice;

    public DeckService(Dice dice) {
        this.dice = dice;
    }

    // ---------- Composition initiale ----------

    public void initDecks(Game g) {
        // --- Potions ---
        Map<String, Integer> potionsComp = new HashMap<>();
        potionsComp.put("FORCE", 6);
        potionsComp.put("ENDURANCE", 6);
        potionsComp.put("VIE", 7);
        potionsComp.put("FOCALISATION", 4);
        potionsComp.put("SANGSUE", 4);

        g.setPotionDeck(buildDeckFromComposition(potionsComp));
        g.setPotionDiscard(new ArrayList<>());

        // --- Rare Potions ---
        Map<String, Integer> elixirsComp = new HashMap<>();
        elixirsComp.put("RESILIENCE", 3);
        elixirsComp.put("RAGE", 3);
        elixirsComp.put("RAPIDITE", 2);
        elixirsComp.put("INVISIBILITE", 2);
        elixirsComp.put("INVULNERABILITE", 1);

        g.setElixirDeck(buildDeckFromComposition(elixirsComp));
        g.setElixirDiscard(new ArrayList<>());

        // --- Actions chasseurs ---
        Map<String, Integer> hunterComp = new HashMap<>();
        hunterComp.put("FUMIGATION_AIL", 3);
        hunterComp.put("FEU_DE_CAMP", 2);
        hunterComp.put("NET", 8);
        hunterComp.put("PIT", 6);
        hunterComp.put("INCENDIAIRE", 2);
        hunterComp.put("PROVOCATION", 4);
        hunterComp.put("AMBUSH", 3);
        hunterComp.put("LONELY", 3);
        hunterComp.put("BLESSED_STAKE", 6);
        hunterComp.put("SACRED_ROSARY", 1);
        hunterComp.put("CHARISMATIQUE", 4);
        hunterComp.put("MARCHAND_ITINERANT", 8);
        hunterComp.put("CRATE_MANOR", 5);
        hunterComp.put("CRATE_LAKE", 5);

        g.setHunterActionsDeck(buildDeckFromComposition(hunterComp));
        g.setHunterActionsDiscard(new ArrayList<>());

        // --- Actions vampire ---
        Map<String, Integer> vampComp = new HashMap<>();
        vampComp.put("PRESENCE_ECRASANTE", 2);
        vampComp.put("CATACLYSME", 2);
        vampComp.put("CLONES_OMBRE", 4);
        vampComp.put("IMAGE_MIROIR", 2);
        vampComp.put("ECLIPSE", 3);
        vampComp.put("BLOOD_MOON", 1);
        vampComp.put("VOILE_DE_BRUME", 3);
        vampComp.put("FAIM_IRREPRESSIBLE", 4);
        vampComp.put("MARQUE_TENEBREUSE", 2);
        vampComp.put("AFFAIBLISSEMENT_OCCULTE", 3);
        vampComp.put("PASSAGE_SECRET", 2);
        vampComp.put("AVIDITE_NOCTURNE", 3);
        vampComp.put("ADVANCED_TRANSMUTATION", 4);
        vampComp.put("PORTAL_INVOCATION_REVENANT", 4);
        vampComp.put("PORTAL_INVOCATION_BAT", 4);

        g.setVampActionsDeck(buildDeckFromComposition(vampComp));
        g.setVampActionsDiscard(new ArrayList<>());
    }

    private List<String> buildDeckFromComposition(Map<String, Integer> composition) {
        List<String> deck = new ArrayList<>();
        for (var e : composition.entrySet()) {
            String cardId = e.getKey();
            int count = (e.getValue() != null ? e.getValue() : 0);
            for (int i = 0; i < count; i++) {
                deck.add(cardId);
            }
        }
        // Mélange initial, une seule fois
        dice.shuffle(deck);
        return deck;
    }

    // ---------- Opérations génériques ----------

    /** Pioche dans un deck ordonné, avec reshuffle auto depuis la défausse. */
    public String draw(List<String> deck, List<String> discard) {
        if (deck == null)
            return null;

        // 1) Si deck vide AVANT pioche, on recrée depuis la défausse
        if (deck.isEmpty() && discard != null && !discard.isEmpty()) {
            dice.shuffle(discard);
            deck.addAll(discard);
            discard.clear();
        }

        if (deck.isEmpty())
            return null;

        // 2) Pioche (top = fin)
        String card = deck.remove(deck.size() - 1);

        // 3) Si le deck vient de tomber à 0, on reshuffle TOUT DE SUITE
        if (deck.isEmpty() && discard != null && !discard.isEmpty()) {
            dice.shuffle(discard);
            deck.addAll(discard);
            discard.clear();
        }

        return card;
    }

    public void putOnTop(List<String> deck, String cardId) {
        if (deck == null || cardId == null)
            return;
        deck.add(cardId); // top = fin
    }

    public void putOnBottom(List<String> deck, String cardId) {
        if (deck == null || cardId == null)
            return;
        deck.add(0, cardId); // bottom = début
    }

    public void discardCard(List<String> discard, String cardId) {
        if (discard == null || cardId == null)
            return;
        discard.add(cardId);
    }

    // ---------- Tailles (affichage / disponibilité) ----------

    public int deckSize(List<String> deck) {
        return (deck != null ? deck.size() : 0);
    }

    public int discardSize(List<String> discard) {
        return (discard != null ? discard.size() : 0);
    }

    /**
     * Taille "effective" du deck : ce qu’on considère comme encore piochable
     * en tenant compte de la défausse (reshuffle auto).
     */
    public int availableSize(List<String> deck, List<String> discard) {
        int deckSize = (deck != null ? deck.size() : 0);
        if (deckSize > 0)
            return deckSize;

        return (discard != null ? discard.size() : 0);
    }

    // ---------- Raccourcis par deck ----------

    public String drawHunterAction(Game g) {
        return draw(g.getHunterActionsDeck(), g.getHunterActionsDiscard());
    }

    public String drawVampAction(Game g) {
        return draw(g.getVampActionsDeck(), g.getVampActionsDiscard());
    }

    public String drawPotion(Game g) {
        return draw(g.getPotionDeck(), g.getPotionDiscard());
    }

    public String drawElixir(Game g) {
        return draw(g.getElixirDeck(), g.getElixirDiscard());
    }

    public void discardHunterAction(Game g, String cardId) {
        discardCard(g.getHunterActionsDiscard(), cardId);
    }

    public void discardVampAction(Game g, String cardId) {
        discardCard(g.getVampActionsDiscard(), cardId);
    }

    public void discardPotion(Game g, String cardId) {
        discardCard(g.getPotionDiscard(), cardId);
    }

    public void discardElixir(Game g, String cardId) {
        discardCard(g.getElixirDiscard(), cardId);
    }

    /**
     * Défausse TOUTES les cartes d'action du joueur, puis vide sa main.
     * À appeler AVANT de changer son rôle si on veut savoir s'il était
     * chasseur ou vampire.
     */
    public void discardAllActionsOf(Game g, Player p) {
        List<String> inv = p.getActions();
        if (inv == null || inv.isEmpty())
            return;

        boolean wasHunter = "HUNTER".equals(p.getRole());

        // On travaille sur une copie pour éviter les soucis pendant le clear()
        var copy = new ArrayList<>(inv);

        for (String card : copy) {
            if (wasHunter) {
                discardHunterAction(g, card);
            }
            // on ne supprime pas ici élément par élément, on videra la liste à la fin
        }

        inv.clear(); // la main d'actions du joueur est vide
    }

    /** Défausse TOUTES les potions du joueur, puis vide sa main de potions. */
    public void discardAllPotionsOf(Game g, Player p) {
        List<String> inv = p.getPotions();
        if (inv == null || inv.isEmpty())
            return;

        var copy = new ArrayList<>(inv);

        for (String card : copy) {
            discardPotion(g, card);
        }

        inv.clear();
    }
}
