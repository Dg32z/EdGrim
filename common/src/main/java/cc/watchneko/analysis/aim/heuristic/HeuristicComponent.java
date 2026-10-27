package cc.watchneko.analysis.aim.heuristic;

import cc.watchneko.utils.anticheat.update.RotationUpdate;

public interface HeuristicComponent {
    void process(final RotationUpdate event);
}
