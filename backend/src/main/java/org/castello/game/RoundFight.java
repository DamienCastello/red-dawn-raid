package org.castello.game;

public class RoundFight {
    private String id;
    private String location;      // "foret", "carriere", "lac", "manoir"
    private String attackerId;    // joueur qui attaque
    private String defenderId;    // joueur qui défend
    private Integer attackerRoll; // jet (null tant que pas lancé)
    private Integer defenderRoll; // jet (null tant que pas lancé)
    private Long resolvedAtMillis;// quand les dégâts ont été appliqués (info)

    public RoundFight() {}

    public RoundFight(String id, String location, String attackerId, String defenderId) {
        this.id = id;
        this.location = location;
        this.attackerId = attackerId;
        this.defenderId = defenderId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getAttackerId() { return attackerId; }
    public void setAttackerId(String attackerId) { this.attackerId = attackerId; }

    public String getDefenderId() { return defenderId; }
    public void setDefenderId(String defenderId) { this.defenderId = defenderId; }

    public Integer getAttackerRoll() { return attackerRoll; }
    public void setAttackerRoll(Integer attackerRoll) { this.attackerRoll = attackerRoll; }

    public Integer getDefenderRoll() { return defenderRoll; }
    public void setDefenderRoll(Integer defenderRoll) { this.defenderRoll = defenderRoll; }

    public Long getResolvedAtMillis() { return resolvedAtMillis; }
    public void setResolvedAtMillis(Long resolvedAtMillis) { this.resolvedAtMillis = resolvedAtMillis; }
}
