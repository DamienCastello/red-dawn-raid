package org.castello.game;

import org.castello.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class Game {
    private String id;
    private GameStatus status;

    // état "tour/raid"
    private int raid;            // n° de raid (1 au start)
    private Phase phase;         // PHASE0 / PHASE1 / PHASE2 / PHASE3 / PHASE4

    private long phaseStartMillis;            // timestamp d’entrée dans la phase courante
    private Phase pendingNextPhase;           // phase à appliquer automatiquement (sinon null)
    private long nextAutoAdvanceAtMillis;     // quand appliquer pendingNextPhase (ms)

    // joueurs dans la partie
    private final List<Player> players = new ArrayList<>();

    // cartes posées au centre (face cachée/visible)
    private List<CenterBoard> center = new ArrayList<>();

    // compteurs des decks/pioches
    private int vampActionsLeft, vampActionsDiscard;
    private int hunterActionsLeft, hunterActionsDiscard;
    private int potionsLeft, potionsDiscard;

    // --- Step 3: messages & fenêtre d’actions ---
    private List<String> messages = new ArrayList<>();   // messages à afficher (préphase3 / phase3)
    private long prePhaseDeadlineMillis;                 // quand se termine la fenêtre PREPHASE3 (ms)
    private final Set<String> readyForPhase3 = new HashSet<>(); // joueurs ayant cliqué “j’ai fini”


    public Game(String id, GameStatus status, int raid) {
        this.id = id;
        this.status = status;
        this.raid = raid;
        this.phase = null;
    }

    // getters de base
    public String getId() { return id; }
    public GameStatus getStatus() { return status; }
    // compat: certains fronts lisent "round" -> on renvoie le raid
    public int getRound() { return raid; }

    public void setStatus(GameStatus status) { this.status = status; }

    // état de partie
    public int getRaid() { return raid; }
    public void setRaid(int raid) { this.raid = raid; }

    public Phase getPhase() { return phase; }
    public void setPhase(Phase phase) { this.phase = phase; }

    // joueurs
    public List<Player> getPlayers() { return players; }

    // centre
    public List<CenterBoard> getCenter() { return center; }
    public void setCenter(List<CenterBoard> center) { this.center = center; }

    // compteurs
    public int getVampActionsLeft() { return vampActionsLeft; }
    public void setVampActionsLeft(int v) { this.vampActionsLeft = v; }

    public int getVampActionsDiscard() { return vampActionsDiscard; }
    public void setVampActionsDiscard(int v) { this.vampActionsDiscard = v; }

    public int getHunterActionsLeft() { return hunterActionsLeft; }
    public void setHunterActionsLeft(int v) { this.hunterActionsLeft = v; }

    public int getHunterActionsDiscard() { return hunterActionsDiscard; }
    public void setHunterActionsDiscard(int v) { this.hunterActionsDiscard = v; }

    public int getPotionsLeft() { return potionsLeft; }
    public void setPotionsLeft(int v) { this.potionsLeft = v; }

    public int getPotionsDiscard() { return potionsDiscard; }
    public void setPotionsDiscard(int v) { this.potionsDiscard = v; }

    // gestion des phases
    public long getPhaseStartMillis() { return phaseStartMillis; }
    public Phase getPendingNextPhase() { return pendingNextPhase; }
    public long getNextAutoAdvanceAtMillis() { return nextAutoAdvanceAtMillis; }

    public void setPhaseStartMillis(long v) { this.phaseStartMillis = v; }
    public void setPendingNextPhase(Phase p) { this.pendingNextPhase = p; }
    public void setNextAutoAdvanceAtMillis(long v) { this.nextAutoAdvanceAtMillis = v; }

    // messages
    public List<String> getMessages() { return messages; }
    public void setMessages(List<String> messages) { this.messages = messages; }

    // fenêtre PREPHASE3
    public long getPrePhaseDeadlineMillis() { return prePhaseDeadlineMillis; }
    public void setPrePhaseDeadlineMillis(long v) { this.prePhaseDeadlineMillis = v; }

    // skip/ready
    public Set<String> getReadyForPhase3() { return readyForPhase3; }
}
