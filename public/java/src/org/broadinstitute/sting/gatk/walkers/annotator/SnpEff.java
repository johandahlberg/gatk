/*
 * Copyright (c) 2011, The Broad Institute
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

package org.broadinstitute.sting.gatk.walkers.annotator;

import org.apache.log4j.Logger;
import org.broadinstitute.sting.commandline.RodBinding;
import org.broadinstitute.sting.gatk.GenomeAnalysisEngine;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.walkers.annotator.interfaces.AnnotatorCompatibleWalker;
import org.broadinstitute.sting.gatk.walkers.annotator.interfaces.InfoFieldAnnotation;
import org.broadinstitute.sting.gatk.walkers.annotator.interfaces.RodRequiringAnnotation;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.codecs.vcf.*;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.variantcontext.VariantContext;

import java.util.*;

/**
 * A set of genomic annotations based on the output of the SnpEff variant effect predictor tool
 * (http://snpeff.sourceforge.net/).
 *
 * For each variant, chooses one of the effects of highest biological impact from the SnpEff
 * output file (which must be provided on the command line via --snpEffFile filename.vcf),
 * and adds annotations on that effect.
 *
 * @author David Roazen
 */
public class SnpEff extends InfoFieldAnnotation implements RodRequiringAnnotation {

    private static Logger logger = Logger.getLogger(SnpEff.class);

    // We refuse to parse SnpEff output files generated by unsupported versions, or
    // lacking a SnpEff version number in the VCF header:
    public static final String[] SUPPORTED_SNPEFF_VERSIONS = { "2.0.4" };
    public static final String SNPEFF_VCF_HEADER_VERSION_LINE_KEY = "SnpEffVersion";
    public static final String SNPEFF_VCF_HEADER_COMMAND_LINE_KEY = "SnpEffCmd";

    // When we write the SnpEff version number and command line to the output VCF, we change
    // the key name slightly so that the output VCF won't be confused in the future for an
    // output file produced by SnpEff directly:
    public static final String OUTPUT_VCF_HEADER_VERSION_LINE_KEY = "Original" + SNPEFF_VCF_HEADER_VERSION_LINE_KEY;
    public static final String OUTPUT_VCF_HEADER_COMMAND_LINE_KEY = "Original" + SNPEFF_VCF_HEADER_COMMAND_LINE_KEY;

    // SnpEff aggregates all effects (and effect metadata) together into a single INFO
    // field annotation with the key EFF:
    public static final String SNPEFF_INFO_FIELD_KEY = "EFF";
    public static final String SNPEFF_EFFECT_METADATA_DELIMITER = "[()]";
    public static final String SNPEFF_EFFECT_METADATA_SUBFIELD_DELIMITER = "\\|";

    // Key names for the INFO field annotations we will add to each record, along
    // with parsing-related information:
    public enum InfoFieldKey {
        EFFECT_KEY            ("SNPEFF_EFFECT",           -1),
        IMPACT_KEY            ("SNPEFF_IMPACT",            0),
        FUNCTIONAL_CLASS_KEY  ("SNPEFF_FUNCTIONAL_CLASS",  1),
        CODON_CHANGE_KEY      ("SNPEFF_CODON_CHANGE",      2),
        AMINO_ACID_CHANGE_KEY ("SNPEFF_AMINO_ACID_CHANGE", 3),
        GENE_NAME_KEY         ("SNPEFF_GENE_NAME",         4),
        GENE_BIOTYPE_KEY      ("SNPEFF_GENE_BIOTYPE",      5),
        TRANSCRIPT_ID_KEY     ("SNPEFF_TRANSCRIPT_ID",     7),
        EXON_ID_KEY           ("SNPEFF_EXON_ID",           8);

        // Actual text of the key
        private final String keyName;

        // Index within the effect metadata subfields from the SnpEff EFF annotation
        // where each key's associated value can be found during parsing.
        private final int fieldIndex;

        InfoFieldKey ( String keyName, int fieldIndex ) {
            this.keyName = keyName;
            this.fieldIndex = fieldIndex;
        }

        public String getKeyName() {
            return keyName;
        }

        public int getFieldIndex() {
            return fieldIndex;
        }
    }

    // Possible SnpEff biological effects. All effect names found in the SnpEff input file
    // are validated against this list.
    public enum EffectType {
        // High-impact effects:
        SPLICE_SITE_ACCEPTOR,
        SPLICE_SITE_DONOR,
        START_LOST,
        EXON_DELETED,
        FRAME_SHIFT,
        STOP_GAINED,
        STOP_LOST,

        // Moderate-impact effects:
        NON_SYNONYMOUS_CODING,
        CODON_CHANGE,
        CODON_INSERTION,
        CODON_CHANGE_PLUS_CODON_INSERTION,
        CODON_DELETION,
        CODON_CHANGE_PLUS_CODON_DELETION,
        UTR_5_DELETED,
        UTR_3_DELETED,

        // Low-impact effects:
        SYNONYMOUS_START,
        NON_SYNONYMOUS_START,
        START_GAINED,
        SYNONYMOUS_CODING,
        SYNONYMOUS_STOP,
        NON_SYNONYMOUS_STOP,

        // Modifiers:
        NONE,
        CHROMOSOME,
        CUSTOM,
        CDS,
        GENE,
        TRANSCRIPT,
        EXON,
        INTRON_CONSERVED,
        UTR_5_PRIME,
        UTR_3_PRIME,
        DOWNSTREAM,
        INTRAGENIC,
        INTERGENIC,
        INTERGENIC_CONSERVED,
        UPSTREAM,
        REGULATION,
        INTRON
    }

    // SnpEff labels each effect as either LOW, MODERATE, or HIGH impact, or as a MODIFIER.
    public enum EffectImpact {
        MODIFIER  (0),
        LOW       (1),
        MODERATE  (2),
        HIGH      (3);

        private final int severityRating;

        EffectImpact ( int severityRating ) {
            this.severityRating = severityRating;
        }

        public boolean isHigherImpactThan ( EffectImpact other ) {
            return this.severityRating > other.severityRating;
        }

        public boolean isSameImpactAs ( EffectImpact other ) {
            return this.severityRating == other.severityRating;
        }
    }

    // SnpEff labels most effects as either CODING or NON_CODING, but sometimes omits this information.
    public enum EffectCoding {
        CODING,
        NON_CODING,
        UNKNOWN
    }

    // SnpEff assigns a functional class to each effect.
    public enum EffectFunctionalClass {
        NONE     (0),
        SILENT   (1),
        MISSENSE (2),
        NONSENSE (3);

        private final int priority;

        EffectFunctionalClass ( int priority ) {
            this.priority = priority;
        }

        public boolean isHigherPriorityThan ( EffectFunctionalClass other ) {
            return this.priority > other.priority;
        }
    }

    public void initialize ( AnnotatorCompatibleWalker walker, GenomeAnalysisEngine toolkit, Set<VCFHeaderLine> headerLines ) {
        // Make sure that we actually have a valid SnpEff rod binding (just in case the user specified -A SnpEff
        // without providing a SnpEff rod via --snpEffFile):
        validateRodBinding(walker.getSnpEffRodBinding());
        RodBinding<VariantContext> snpEffRodBinding = walker.getSnpEffRodBinding();

        // Make sure that the SnpEff version number and command-line header lines are present in the VCF header of
        // the SnpEff rod, and that the file was generated by a supported version of SnpEff:
        VCFHeader snpEffVCFHeader = VCFUtils.getVCFHeadersFromRods(toolkit, Arrays.asList(snpEffRodBinding.getName())).get(snpEffRodBinding.getName());
        VCFHeaderLine snpEffVersionLine = snpEffVCFHeader.getOtherHeaderLine(SNPEFF_VCF_HEADER_VERSION_LINE_KEY);
        VCFHeaderLine snpEffCommandLine = snpEffVCFHeader.getOtherHeaderLine(SNPEFF_VCF_HEADER_COMMAND_LINE_KEY);

        checkSnpEffVersion(snpEffVersionLine);
        checkSnpEffCommandLine(snpEffCommandLine);

        // If everything looks ok, add the SnpEff version number and command-line header lines to the
        // header of the VCF output file, changing the key names so that our output file won't be
        // mistaken in the future for a SnpEff output file:
        headerLines.add(new VCFHeaderLine(OUTPUT_VCF_HEADER_VERSION_LINE_KEY, snpEffVersionLine.getValue()));
        headerLines.add(new VCFHeaderLine(OUTPUT_VCF_HEADER_COMMAND_LINE_KEY, snpEffCommandLine.getValue()));
    }

    public Map<String, Object> annotate ( RefMetaDataTracker tracker, AnnotatorCompatibleWalker walker, ReferenceContext ref, Map<String, AlignmentContext> stratifiedContexts, VariantContext vc ) {
        RodBinding<VariantContext> snpEffRodBinding = walker.getSnpEffRodBinding();

        // Get only SnpEff records that start at this locus, not merely span it:
        List<VariantContext> snpEffRecords = tracker.getValues(snpEffRodBinding, ref.getLocus());

        // Within this set, look for a SnpEff record whose ref/alt alleles match the record to annotate.
        // If there is more than one such record, we only need to pick the first one, since the biological
        // effects will be the same across all such records:
        VariantContext matchingRecord = getMatchingSnpEffRecord(snpEffRecords, vc);
        if ( matchingRecord == null ) {
            return null;
        }

        // Parse the SnpEff INFO field annotation from the matching record into individual effect objects:
        List<SnpEffEffect> effects = parseSnpEffRecord(matchingRecord);
        if ( effects.size() == 0 ) {
            return null;
        }

        // Add only annotations for one of the most biologically-significant effects from this set:
        SnpEffEffect mostSignificantEffect = getMostSignificantEffect(effects);
        return mostSignificantEffect.getAnnotations();
    }

    private void validateRodBinding ( RodBinding<VariantContext> snpEffRodBinding ) {
        if ( snpEffRodBinding == null || ! snpEffRodBinding.isBound() ) {
            throw new UserException("The SnpEff annotator requires that a SnpEff VCF output file be provided " +
                                    "as a rodbinding on the command line via the --snpEffFile option, but " +
                                    "no SnpEff rodbinding was found.");
        }
    }

    private void checkSnpEffVersion ( VCFHeaderLine snpEffVersionLine ) {
        if ( snpEffVersionLine == null || snpEffVersionLine.getValue() == null || snpEffVersionLine.getValue().trim().length() == 0 ) {
            throw new UserException("Could not find a " + SNPEFF_VCF_HEADER_VERSION_LINE_KEY + " entry in the VCF header for the SnpEff " +
                                    "input file, and so could not verify that the file was generated by a supported version of SnpEff (" +
                                    Arrays.toString(SUPPORTED_SNPEFF_VERSIONS) + ")");
        }

        String snpEffVersionString = snpEffVersionLine.getValue().replaceAll("\"", "").split(" ")[0];

        if ( ! isSupportedSnpEffVersion(snpEffVersionString) ) {
            throw new UserException("The version of SnpEff used to generate the SnpEff input file (" + snpEffVersionString + ") " +
                                    "is not currently supported by the GATK. Supported versions are: " + Arrays.toString(SUPPORTED_SNPEFF_VERSIONS));
        }
    }

    private void checkSnpEffCommandLine ( VCFHeaderLine snpEffCommandLine ) {
        if ( snpEffCommandLine == null || snpEffCommandLine.getValue() == null || snpEffCommandLine.getValue().trim().length() == 0 ) {
            throw new UserException("Could not find a " + SNPEFF_VCF_HEADER_COMMAND_LINE_KEY + " entry in the VCF header for the SnpEff " +
                                    "input file, which should be added by all supported versions of SnpEff (" +
                                    Arrays.toString(SUPPORTED_SNPEFF_VERSIONS) + ")");
        }
    }

    private boolean isSupportedSnpEffVersion ( String versionString ) {
        for ( String supportedVersion : SUPPORTED_SNPEFF_VERSIONS ) {
            if ( supportedVersion.equals(versionString) ) {
                return true;
            }
        }

        return false;
    }

    private VariantContext getMatchingSnpEffRecord ( List<VariantContext> snpEffRecords, VariantContext vc ) {
        for ( VariantContext snpEffRecord : snpEffRecords ) {
            if ( snpEffRecord.hasSameAlternateAllelesAs(vc) && snpEffRecord.getReference().equals(vc.getReference()) ) {
                return snpEffRecord;
            }
        }

        return null;
    }

    private List<SnpEffEffect> parseSnpEffRecord ( VariantContext snpEffRecord ) {
        List<SnpEffEffect> parsedEffects = new ArrayList<SnpEffEffect>();

        Object effectFieldValue = snpEffRecord.getAttribute(SNPEFF_INFO_FIELD_KEY);
        if ( effectFieldValue == null ) {
            return parsedEffects;
        }

        // The VCF codec stores multi-valued fields as a List<String>, and single-valued fields as a String.
        // We can have either in the case of SnpEff, since there may be one or more than one effect in this record.
        List<String> individualEffects;
        if ( effectFieldValue instanceof List ) {
            individualEffects = (List<String>)effectFieldValue;
        }
        else {
            individualEffects = Arrays.asList((String)effectFieldValue);
        }

        for ( String effectString : individualEffects ) {
            String[] effectNameAndMetadata = effectString.split(SNPEFF_EFFECT_METADATA_DELIMITER);

            if ( effectNameAndMetadata.length != 2 ) {
                logger.warn(String.format("Malformed SnpEff effect field at %s:%d, skipping: %s",
                                          snpEffRecord.getChr(), snpEffRecord.getStart(), effectString));
                continue;
            }

            String effectName = effectNameAndMetadata[0];
            String[] effectMetadata = effectNameAndMetadata[1].split(SNPEFF_EFFECT_METADATA_SUBFIELD_DELIMITER, -1);

            SnpEffEffect parsedEffect = new SnpEffEffect(effectName, effectMetadata);

            if ( parsedEffect.isWellFormed() ) {
                parsedEffects.add(parsedEffect);
            }
            else {
                logger.warn(String.format("Skipping malformed SnpEff effect field at %s:%d. Error was: \"%s\". Field was: \"%s\"",
                                          snpEffRecord.getChr(), snpEffRecord.getStart(), parsedEffect.getParseError(), effectString));
            }
        }

        return parsedEffects;
    }

    private SnpEffEffect getMostSignificantEffect ( List<SnpEffEffect> effects ) {
        SnpEffEffect mostSignificantEffect = null;

        for ( SnpEffEffect effect : effects ) {
            if ( mostSignificantEffect == null ||
                 effect.isHigherImpactThan(mostSignificantEffect) ) {

                mostSignificantEffect = effect;
            }
        }

        return mostSignificantEffect;
    }

    public List<String> getKeyNames() {
        return Arrays.asList( InfoFieldKey.EFFECT_KEY.getKeyName(),
                              InfoFieldKey.IMPACT_KEY.getKeyName(),
                              InfoFieldKey.FUNCTIONAL_CLASS_KEY.getKeyName(),
                              InfoFieldKey.CODON_CHANGE_KEY.getKeyName(),
                              InfoFieldKey.AMINO_ACID_CHANGE_KEY.getKeyName(),
                              InfoFieldKey.GENE_NAME_KEY.getKeyName(),
                              InfoFieldKey.GENE_BIOTYPE_KEY.getKeyName(),
                              InfoFieldKey.TRANSCRIPT_ID_KEY.getKeyName(),
                              InfoFieldKey.EXON_ID_KEY.getKeyName()
                            );
    }

    public List<VCFInfoHeaderLine> getDescriptions() {
        return Arrays.asList(
            new VCFInfoHeaderLine(InfoFieldKey.EFFECT_KEY.getKeyName(),            1, VCFHeaderLineType.String,  "The highest-impact effect resulting from the current variant (or one of the highest-impact effects, if there is a tie)"),
            new VCFInfoHeaderLine(InfoFieldKey.IMPACT_KEY.getKeyName(),            1, VCFHeaderLineType.String,  "Impact of the highest-impact effect resulting from the current variant " + Arrays.toString(EffectImpact.values())),
            new VCFInfoHeaderLine(InfoFieldKey.FUNCTIONAL_CLASS_KEY.getKeyName(),  1, VCFHeaderLineType.String,  "Functional class of the highest-impact effect resulting from the current variant: " + Arrays.toString(EffectFunctionalClass.values())),
            new VCFInfoHeaderLine(InfoFieldKey.CODON_CHANGE_KEY.getKeyName(),      1, VCFHeaderLineType.String,  "Old/New codon for the highest-impact effect resulting from the current variant"),
            new VCFInfoHeaderLine(InfoFieldKey.AMINO_ACID_CHANGE_KEY.getKeyName(), 1, VCFHeaderLineType.String,  "Old/New amino acid for the highest-impact effect resulting from the current variant (in HGVS style)"),
            new VCFInfoHeaderLine(InfoFieldKey.GENE_NAME_KEY.getKeyName(),         1, VCFHeaderLineType.String,  "Gene name for the highest-impact effect resulting from the current variant"),
            new VCFInfoHeaderLine(InfoFieldKey.GENE_BIOTYPE_KEY.getKeyName(),      1, VCFHeaderLineType.String,  "Gene biotype for the highest-impact effect resulting from the current variant"),
            new VCFInfoHeaderLine(InfoFieldKey.TRANSCRIPT_ID_KEY.getKeyName(),     1, VCFHeaderLineType.String,  "Transcript ID for the highest-impact effect resulting from the current variant"),
            new VCFInfoHeaderLine(InfoFieldKey.EXON_ID_KEY.getKeyName(),           1, VCFHeaderLineType.String,  "Exon ID for the highest-impact effect resulting from the current variant")
        );
    }

    /**
     * Helper class to parse, validate, and store a single SnpEff effect and its metadata.
     */
    protected static class SnpEffEffect {
        private EffectType effect;
        private EffectImpact impact;
        private EffectFunctionalClass functionalClass;
        private String codonChange;
        private String aminoAcidChange;
        private String geneName;
        private String geneBiotype;
        private EffectCoding coding;
        private String transcriptID;
        private String exonID;

        private String parseError = null;
        private boolean isWellFormed = true;

        private static final int EXPECTED_NUMBER_OF_METADATA_FIELDS = 9;
        private static final int NUMBER_OF_METADATA_FIELDS_UPON_EITHER_WARNING_OR_ERROR = 10;
        private static final int NUMBER_OF_METADATA_FIELDS_UPON_BOTH_WARNING_AND_ERROR = 11;

        // If there is either a warning OR an error, it will be in the last field. If there is both
        // a warning AND an error, the warning will be in the second-to-last field, and the error will
        // be in the last field.
        private static final int SNPEFF_WARNING_OR_ERROR_FIELD_UPON_SINGLE_ERROR = NUMBER_OF_METADATA_FIELDS_UPON_EITHER_WARNING_OR_ERROR - 1;
        private static final int SNPEFF_WARNING_FIELD_UPON_BOTH_WARNING_AND_ERROR = NUMBER_OF_METADATA_FIELDS_UPON_BOTH_WARNING_AND_ERROR - 2;
        private static final int SNPEFF_ERROR_FIELD_UPON_BOTH_WARNING_AND_ERROR = NUMBER_OF_METADATA_FIELDS_UPON_BOTH_WARNING_AND_ERROR - 1;

        // Position of the field indicating whether the effect is coding or non-coding. This field is used
        // in selecting the most significant effect, but is not included in the annotations we return
        // since it can be deduced from the SNPEFF_GENE_BIOTYPE field.
        private static final int SNPEFF_CODING_FIELD_INDEX = 6;

        public SnpEffEffect ( String effectName, String[] effectMetadata ) {
            parseEffectName(effectName);
            parseEffectMetadata(effectMetadata);
        }

        private void parseEffectName ( String effectName ) {
            try {
                effect = EffectType.valueOf(effectName);
            }
            catch ( IllegalArgumentException e ) {
                parseError(String.format("%s is not a recognized effect type", effectName));
            }
        }

        private void parseEffectMetadata ( String[] effectMetadata ) {
            if ( effectMetadata.length != EXPECTED_NUMBER_OF_METADATA_FIELDS ) {
                if ( effectMetadata.length == NUMBER_OF_METADATA_FIELDS_UPON_EITHER_WARNING_OR_ERROR ) {
                    parseError(String.format("SnpEff issued the following warning or error: \"%s\"",
                                             effectMetadata[SNPEFF_WARNING_OR_ERROR_FIELD_UPON_SINGLE_ERROR]));
                }
                else if ( effectMetadata.length == NUMBER_OF_METADATA_FIELDS_UPON_BOTH_WARNING_AND_ERROR ) {
                    parseError(String.format("SnpEff issued the following warning: \"%s\", and the following error: \"%s\"",
                                             effectMetadata[SNPEFF_WARNING_FIELD_UPON_BOTH_WARNING_AND_ERROR],
                                             effectMetadata[SNPEFF_ERROR_FIELD_UPON_BOTH_WARNING_AND_ERROR]));
                }
                else {
                    parseError(String.format("Wrong number of effect metadata fields. Expected %d but found %d",
                                             EXPECTED_NUMBER_OF_METADATA_FIELDS, effectMetadata.length));
                }

                return;
            }

            // The impact field will never be empty, and should always contain one of the enumerated values:
            try {
                impact = EffectImpact.valueOf(effectMetadata[InfoFieldKey.IMPACT_KEY.getFieldIndex()]);
            }
            catch ( IllegalArgumentException e ) {
                parseError(String.format("Unrecognized value for effect impact: %s", effectMetadata[InfoFieldKey.IMPACT_KEY.getFieldIndex()]));
            }

            // The functional class field will be empty when the effect has no functional class associated with it:
            if ( effectMetadata[InfoFieldKey.FUNCTIONAL_CLASS_KEY.getFieldIndex()].trim().length() > 0 ) {
                try {
                    functionalClass = EffectFunctionalClass.valueOf(effectMetadata[InfoFieldKey.FUNCTIONAL_CLASS_KEY.getFieldIndex()]);
                }
                catch ( IllegalArgumentException e ) {
                    parseError(String.format("Unrecognized value for effect functional class: %s", effectMetadata[InfoFieldKey.FUNCTIONAL_CLASS_KEY.getFieldIndex()]));
                }
            }
            else {
                functionalClass = EffectFunctionalClass.NONE;
            }

            codonChange = effectMetadata[InfoFieldKey.CODON_CHANGE_KEY.getFieldIndex()];
            aminoAcidChange = effectMetadata[InfoFieldKey.AMINO_ACID_CHANGE_KEY.getFieldIndex()];
            geneName = effectMetadata[InfoFieldKey.GENE_NAME_KEY.getFieldIndex()];
            geneBiotype = effectMetadata[InfoFieldKey.GENE_BIOTYPE_KEY.getFieldIndex()];

            // The coding field will be empty when SnpEff has no coding info for the effect:
            if ( effectMetadata[SNPEFF_CODING_FIELD_INDEX].trim().length() > 0 ) {
                try {
                    coding = EffectCoding.valueOf(effectMetadata[SNPEFF_CODING_FIELD_INDEX]);
                }
                catch ( IllegalArgumentException e ) {
                    parseError(String.format("Unrecognized value for effect coding: %s", effectMetadata[SNPEFF_CODING_FIELD_INDEX]));
                }
            }
            else {
                coding = EffectCoding.UNKNOWN;
            }

            transcriptID = effectMetadata[InfoFieldKey.TRANSCRIPT_ID_KEY.getFieldIndex()];
            exonID = effectMetadata[InfoFieldKey.EXON_ID_KEY.getFieldIndex()];
        }

        private void parseError ( String message ) {
            isWellFormed = false;

            // Cache only the first error encountered:
            if ( parseError == null ) {
                parseError = message;
            }
        }

        public boolean isWellFormed() {
            return isWellFormed;
        }

        public String getParseError() {
            return parseError == null ? "" : parseError;
        }

        public boolean isCoding() {
            return coding == EffectCoding.CODING;
        }

        public boolean isHigherImpactThan ( SnpEffEffect other ) {
            // If one effect is within a coding gene and the other is not, the effect that is
            // within the coding gene has higher impact:

            if ( isCoding() && ! other.isCoding() ) {
                return true;
            }
            else if ( ! isCoding() && other.isCoding() ) {
                return false;
            }

            // Otherwise, both effects are either in or not in a coding gene, so we compare the impacts
            // of the effects themselves. Effects with the same impact are tie-broken using the
            // functional class of the effect:

            if ( impact.isHigherImpactThan(other.impact) ) {
                return true;
            }
            else if ( impact.isSameImpactAs(other.impact) ) {
                return functionalClass.isHigherPriorityThan(other.functionalClass);
            }

            return false;
        }

        public Map<String, Object> getAnnotations() {
            Map<String, Object> annotations = new LinkedHashMap<String, Object>(Utils.optimumHashSize(InfoFieldKey.values().length));

            addAnnotation(annotations, InfoFieldKey.EFFECT_KEY.getKeyName(), effect.toString());
            addAnnotation(annotations, InfoFieldKey.IMPACT_KEY.getKeyName(), impact.toString());
            addAnnotation(annotations, InfoFieldKey.FUNCTIONAL_CLASS_KEY.getKeyName(), functionalClass.toString());
            addAnnotation(annotations, InfoFieldKey.CODON_CHANGE_KEY.getKeyName(), codonChange);
            addAnnotation(annotations, InfoFieldKey.AMINO_ACID_CHANGE_KEY.getKeyName(), aminoAcidChange);
            addAnnotation(annotations, InfoFieldKey.GENE_NAME_KEY.getKeyName(), geneName);
            addAnnotation(annotations, InfoFieldKey.GENE_BIOTYPE_KEY.getKeyName(), geneBiotype);
            addAnnotation(annotations, InfoFieldKey.TRANSCRIPT_ID_KEY.getKeyName(), transcriptID);
            addAnnotation(annotations, InfoFieldKey.EXON_ID_KEY.getKeyName(), exonID);

            return annotations;
        }

        private void addAnnotation ( Map<String, Object> annotations, String keyName, String keyValue ) {
            // Only add annotations for keys associated with non-empty values:
            if ( keyValue != null && keyValue.trim().length() > 0 ) {
                annotations.put(keyName, keyValue);
            }
        }
    }
}
