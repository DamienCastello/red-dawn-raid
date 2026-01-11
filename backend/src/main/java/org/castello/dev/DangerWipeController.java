package org.castello.dev;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@ConditionalOnProperty(name = "danger.wipe.enabled", havingValue = "true")
public class DangerWipeController {

    private final JdbcTemplate jdbc;

    public DangerWipeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * DANGER: wipe TOTAL, public, sans auth.
     * Active uniquement si danger.wipe.enabled=true
     */
    @PostMapping("/wipe")
    public ResponseEntity<?> wipe(@RequestParam(name = "confirm", required = false) String confirm) {
        if (!"YES".equals(confirm)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Ajouter ?confirm=YES pour confirmer."));
        }

        // (optionnel) bloc anti-accident si tu veux empêcher en prod:
        // if ("prod".equals(System.getProperty("spring.profiles.active"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        final String exclude = "flyway_schema_history";

        String sql = """
                DO $$
                DECLARE r RECORD;
                BEGIN
                  FOR r IN (SELECT tablename
                            FROM pg_tables
                            WHERE schemaname = 'public'
                              AND tablename <> '%s')
                  LOOP
                    EXECUTE 'TRUNCATE TABLE ' || quote_ident(r.tablename) || ' RESTART IDENTITY CASCADE';
                  END LOOP;
                END $$;
                """.formatted(exclude);

        try {
            jdbc.execute(sql);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "wipe failed: " + e.getMessage());
        }

        return ResponseEntity.ok(Map.of("status", "ok", "message", "Base vidée."));
    }
}
