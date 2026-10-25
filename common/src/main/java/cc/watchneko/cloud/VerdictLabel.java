package cc.watchneko.cloud;

import com.google.gson.annotations.SerializedName;

/**
 * A verdict as the cloud classified it, by applying its own classification thresholds to the score.
 *
 * <p>These are the only three conclusions the cloud may reach. {@code uncertain} is a first-class
 * outcome, not an error: the model is allowed to decline, and the plugin then simply gets no evidence.
 */
public enum VerdictLabel {

    @SerializedName("cheat_like")
    CHEAT_LIKE,

    @SerializedName("legit_like")
    LEGIT_LIKE,

    @SerializedName("uncertain")
    UNCERTAIN
}
