package cc.watchneko.cloud;

import com.google.gson.annotations.SerializedName;

/**
 * The ground truth of a batch of samples, fixed when a recording session starts.
 *
 * <p>Labelling is a decision made once, at recording time, and travels with the batch — the cloud never
 * sorts unlabelled samples afterwards.
 */
public enum Label {

    @SerializedName("cheat")
    CHEAT,

    @SerializedName("legit")
    LEGIT
}
