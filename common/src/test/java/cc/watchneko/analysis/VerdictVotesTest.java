package cc.watchneko.analysis;

import cc.watchneko.cloud.VerdictLabel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerdictVotesTest {

    private static void add(final VerdictVotes votes, final VerdictLabel label, final int times) {
        for (int i = 0; i < times; i++) {
            votes.add(label);
        }
    }

    @Test
    void aPartialWindowNeverFlags() {
        final VerdictVotes votes = new VerdictVotes(10, 6);
        add(votes, VerdictLabel.CHEAT_LIKE, 5);
        assertFalse(votes.isFull());
        assertFalse(votes.shouldFlag());
    }

    @Test
    void enoughCheatVerdictsInAFullWindowFlag() {
        final VerdictVotes votes = new VerdictVotes(10, 6);
        add(votes, VerdictLabel.CHEAT_LIKE, 6);
        add(votes, VerdictLabel.LEGIT_LIKE, 4);
        assertTrue(votes.shouldFlag());
        assertEquals(6, votes.cheatVotes());
    }

    @Test
    void tooFewCheatVerdictsDoNotFlag() {
        final VerdictVotes votes = new VerdictVotes(10, 6);
        add(votes, VerdictLabel.CHEAT_LIKE, 5);
        add(votes, VerdictLabel.LEGIT_LIKE, 5);
        assertTrue(votes.isFull());
        assertFalse(votes.shouldFlag());
    }

    @Test
    void uncertainVerdictsAreNeitherEvidenceNorExoneration() {
        final VerdictVotes votes = new VerdictVotes(10, 6);
        add(votes, VerdictLabel.UNCERTAIN, 10);
        assertTrue(votes.isFull());
        assertFalse(votes.shouldFlag());
        assertEquals(0, votes.cheatVotes());
    }

    @Test
    void theWindowSlidesSoOldEvidenceStopsCounting() {
        final VerdictVotes votes = new VerdictVotes(10, 6);
        add(votes, VerdictLabel.CHEAT_LIKE, 6);
        add(votes, VerdictLabel.LEGIT_LIKE, 4);
        assertTrue(votes.shouldFlag());

        add(votes, VerdictLabel.LEGIT_LIKE, 5);
        assertEquals(10, votes.size());
        assertFalse(votes.shouldFlag(), "the cheat verdicts have slid out of the window");
    }

    @Test
    void aModelThatOnlyEverSaysUncertainCannotCauseAViolation() {
        final VerdictVotes votes = new VerdictVotes(10, 6);
        for (int i = 0; i < 100; i++) {
            votes.add(VerdictLabel.UNCERTAIN);
            assertFalse(votes.shouldFlag());
        }
    }
}
