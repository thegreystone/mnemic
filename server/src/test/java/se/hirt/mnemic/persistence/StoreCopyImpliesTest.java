package se.hirt.mnemic.persistence;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.Fact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * Env-gated (MNEMIC_STORE_COPY): the implies sequence a caller reported failing, run on a copy of a real store and
 * undone. Prints what the deriver made of it.
 */
class StoreCopyImpliesTest {

	@Test
	void repro() {
		String copy = System.getenv("MNEMIC_STORE_COPY");
		Assumptions.assumeTrue(copy != null && Files.exists(Path.of(copy, "mnemic.db")));
		try (Engine e = TestHomes.engine(Path.of(copy))) {
			System.out.println("== sibling implies before: " + e.predicates().impliesOf("sibling_of"));
			System.out.println("== gender predicate: " + e.predicates().get("gender")
					.map(p -> p.range() + " seed=" + p.seed() + " inferred=" + p.isInferred()));
			var out = e.correctPredicate("sibling_of",
					Map.of("implies",
							Map.of("sister", Map.of("gender", "female"), "brother", Map.of("gender", "male"))),
					"we trust them");
			System.out.println("== correct reply: " + out);
			System.out.println("== sibling implies after: " + e.predicates().impliesOf("sibling_of"));
			RememberOutcome fam = remember(e,
					"Nibtest Parent is Nibtest Kid's father. Nibtest Aunt is Nibtest Parent's sister.",
					proposal().entity("e1", "Nibtest Parent", "person").entity("e2", "Nibtest Kid", "person")
							.entity("e3", "Nibtest Aunt", "person")
							.fact(fact("e1", "parent_of", "e2", "father", null, null, null, null, null, null))
							.fact(fact("e3", "sibling_of", "e1", "sister", null, null, null, null, null, null)));
			System.out.println("== applied: " + fam.applied().facts() + " warnings " + fam.applied().warnings()
					+ " questions " + fam.applied().questions());
			System.out.println("== derived: " + fam.derived());
			long aunt = e.entities().byRef("Nibtest Aunt").orElseThrow().id();
			for (Fact f : e.facts().factsOf(aunt)) {
				System.out.println("== aunt fact " + f.ref() + " " + f.predicate() + " [" + f.qualifier() + "] "
						+ f.status() + " " + f.derivationKind() + " mode=" + f.mode() + " :: " + f.rendering());
			}
			List<Map<String, Object>> gaps = e.consolidate(true).attributeUnknown();
			System.out.println("== gaps: "
					+ gaps.stream().filter(g -> String.valueOf(g.get("name")).startsWith("Nibtest")).toList());
			e.forget(fam.observation().observationId());
		}
	}
}
