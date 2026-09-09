package dev.mzhin.hatenacal.venue;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 会場（docs/data-model.md「venue — 会場」）。 */
public interface VenueRepository extends JpaRepository<Venue, Long> {

    Optional<Venue> findByVenueKey(String venueKey);
}
