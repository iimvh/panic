package com.panic.agents.digest.digest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DigestRepository extends JpaRepository<Digest, Long> {

	Optional<Digest> findByDigestDate(LocalDate digestDate);

	boolean existsByDigestDate(LocalDate digestDate);

	/** Archive listing: newest day first. */
	List<Digest> findAllByOrderByDigestDateDesc();
}
