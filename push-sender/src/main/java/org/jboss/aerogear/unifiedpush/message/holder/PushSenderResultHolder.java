package org.jboss.aerogear.unifiedpush.message.holder;

import com.google.android.gcm.server.MulticastResult;

import java.io.Serializable;
import java.util.List;

/**
 * Results of push sending are held in the class and transmitted by JavaEE to be processed later.
 */
public class PushSenderResultHolder implements Serializable {

    private MulticastResult result;
    private String variantId;
    private List<String> targets;


    public void setResult(MulticastResult result) {
        this.result = result;
    }

    public MulticastResult getResult() {
        return result;
    }

    public void setVariantId(String variantId) {
        this.variantId = variantId;
    }

    public String getVariantId() {
        return variantId;
    }

    public void setTargets(List<String> targets) {
        this.targets = targets;
    }

    public List<String> getTargets() {
        return targets;
    }
}
