package org.castello.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "games")
public class GameEntity {
    @Id
    private String id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state", columnDefinition = "jsonb", nullable = false)
    private String stateJson;

    @Column(name = "version")
    private Long version;

    public GameEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getStateJson() { return stateJson; }
    public void setStateJson(String stateJson) { this.stateJson = stateJson; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
