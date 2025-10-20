package org.castello.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<PlayerEntity, String> {
    Optional<PlayerEntity> findByUserId(String userId);
    Optional<PlayerEntity> findByUserIdAndGameId(String userId, String gameId);
    List<PlayerEntity> findByGameId(String gameId);
}
