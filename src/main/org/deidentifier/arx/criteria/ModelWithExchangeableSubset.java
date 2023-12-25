package org.deidentifier.arx.criteria;

import org.deidentifier.arx.DataSubset;

/**
 *
 */
public interface ModelWithExchangeableSubset {

    /**
     * Clones the PrivacyCriterion and replaces its DataSubset with given dataSubset
     * @param dataSubset dataSubset used for replacement
     * @return Cloned privacy criterion with new dataSubset
     */
    PrivacyCriterion cloneAndExchangeDataSubset(DataSubset dataSubset);
}
