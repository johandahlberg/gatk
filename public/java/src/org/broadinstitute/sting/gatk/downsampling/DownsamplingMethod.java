/*
 * Copyright (c) 2012, The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.downsampling;

import org.broadinstitute.sting.gatk.walkers.ActiveRegionWalker;
import org.broadinstitute.sting.gatk.walkers.LocusWalker;
import org.broadinstitute.sting.gatk.walkers.Walker;
import org.broadinstitute.sting.utils.exceptions.UserException;

/**
 * Describes the method for downsampling reads at a given locus.
 */

public class DownsamplingMethod {
    /**
     * Type of downsampling to perform.
     */
    public final DownsampleType type;

    /**
     * Actual downsampling target is specified as an integer number of reads.
     */
    public final Integer toCoverage;

    /**
     * Actual downsampling target is specified as a fraction of total available reads.
     */
    public final Double toFraction;

    /**
     * Use the legacy downsampling implementation instead of the newer implementation?
     */
    public final boolean useLegacyDownsampler;

    /**
     * Expresses no downsampling applied at all.
     */
    public static final DownsamplingMethod NONE = new DownsamplingMethod(DownsampleType.NONE,null,null,false);

    /**
     * Default type to use if no type is specified
     */
    public static DownsampleType DEFAULT_DOWNSAMPLING_TYPE = DownsampleType.BY_SAMPLE;

    /**
     * Default target coverage for locus-based traversals
     */
    public static int DEFAULT_LOCUS_BASED_TRAVERSAL_DOWNSAMPLING_COVERAGE = 1000;

    public DownsamplingMethod( DownsampleType type, Integer toCoverage, Double toFraction, boolean useLegacyDownsampler ) {
        this.type = type != null ? type : DEFAULT_DOWNSAMPLING_TYPE;
        this.toCoverage = toCoverage;
        this.toFraction = toFraction;
        this.useLegacyDownsampler = useLegacyDownsampler;

        if ( type == DownsampleType.NONE ) {
            toCoverage = null;
            toFraction = null;
        }

        validate();
    }

    private void validate() {
        // Can't leave toFraction and toCoverage null unless type is NONE
        if ( type != DownsampleType.NONE && toFraction == null && toCoverage == null )
            throw new UserException.CommandLineException("Must specify either toFraction or toCoverage when downsampling.");

        // Fraction and coverage cannot both be specified.
        if ( toFraction != null && toCoverage != null )
            throw new UserException.CommandLineException("Downsampling coverage and fraction are both specified.  Please choose only one.");

        // toCoverage must be > 0 when specified
        if ( toCoverage != null && toCoverage <= 0 ) {
            throw new UserException.CommandLineException("toCoverage must be > 0 when downsampling to coverage");
        }

        // toFraction must be >= 0.0 and <= 1.0 when specified
        if ( toFraction != null && (toFraction < 0.0 || toFraction > 1.0) ) {
            throw new UserException.CommandLineException("toFraction must be >= 0.0 and <= 1.0 when downsampling to a fraction of reads");
        }
    }

    public void checkCompatibilityWithWalker( Walker walker ) {
        boolean isLocusTraversal = walker instanceof LocusWalker || walker instanceof ActiveRegionWalker;

        if ( ! isLocusTraversal && useLegacyDownsampler && toCoverage != null ) {
            throw new UserException.CommandLineException("Downsampling to coverage for read-based traversals (eg., ReadWalkers) is not supported in the legacy downsampling implementation. " +
                                                         "The newer downsampling implementation does not have this limitation.");
        }

        if ( isLocusTraversal && ! useLegacyDownsampler && type == DownsampleType.ALL_READS && toCoverage != null ) {
            throw new UserException.CommandLineException("Downsampling to coverage with the ALL_READS method for locus-based traversals (eg., LocusWalkers) is not yet supported in the new downsampling implementation (though it is supported for ReadWalkers). " +
                                                         "You can run with --use_legacy_downsampler for a broken and poorly-maintained implementation of ALL_READS to-coverage downsampling, but this is not recommended.");
        }
    }

    public String toString() {
        StringBuilder builder = new StringBuilder("Downsampling Settings: ");

        if ( type == DownsampleType.NONE ) {
            builder.append("No downsampling");
        }
        else {
            builder.append(String.format("Method: %s, ", type));

            if ( toCoverage != null ) {
                builder.append(String.format("Target Coverage: %d, ", toCoverage));
            }
            else {
                builder.append(String.format("Target Fraction: %.2f, ", toFraction));
            }

            if ( useLegacyDownsampler ) {
                builder.append("Using the legacy downsampling implementation");
            }
            else {
                builder.append("Using the new downsampling implementation");
            }
        }

        return builder.toString();
    }

    public static DownsamplingMethod getDefaultDownsamplingMethod( Walker walker, boolean useLegacyDownsampler ) {
        if ( walker instanceof LocusWalker || walker instanceof ActiveRegionWalker ) {
            return new DownsamplingMethod(DEFAULT_DOWNSAMPLING_TYPE, DEFAULT_LOCUS_BASED_TRAVERSAL_DOWNSAMPLING_COVERAGE,
                                          null, useLegacyDownsampler);
        }
        else {
            // Downsampling is off by default for non-locus-based traversals
            return new DownsamplingMethod(DownsampleType.NONE, null, null, useLegacyDownsampler);
        }
    }
}
