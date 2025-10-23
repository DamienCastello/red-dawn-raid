package org.castello.dev;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/dev")
@Profile("dev") // <-- cette classe N'EXISTE PAS sauf si profil 'dev' est actif
public class DevController {

    private final JdbcTemplate jdbc;

    public DevController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Vide toutes les tables du schéma 'public' (local dev uniquement).
     * Sécurisé par un petit 'confirm=YES' pour éviter les clics malheureux.
     */
    @PostMapping("/wipe")
    public ResponseEntity<?> wipe(@RequestParam Optional<String> confirm) {
        if (confirm.isEmpty() || !"YES".equals(confirm.get())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Ajouter ?confirm=YES pour confirmer la suppression totale."));
        }

        // Si tu utilises Flyway, exclue la table d'historique:
        final String exclude = "flyway_schema_history";

        // TRUNCATE toutes les tables du schéma public (sauf exclusions), reset des séquences, cascade.
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

        jdbc.execute(sql);

        return ResponseEntity.ok(Map.of("status", "ok", "message", "Base vidée (dev local)."));
    }
}
