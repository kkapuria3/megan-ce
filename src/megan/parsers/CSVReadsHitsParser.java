/*
 *  Copyright (C) 2016 Daniel H. Huson
 *
 *  (Some files contain contributions from other authors, who are then mentioned separately.)
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package megan.parsers;

import jloda.util.*;
import megan.algorithms.MinSupportFilter;
import megan.classification.Classification;
import megan.classification.ClassificationManager;
import megan.classification.IdMapper;
import megan.classification.IdParser;
import megan.core.ClassificationType;
import megan.core.DataTable;
import megan.core.Document;
import megan.fx.NotificationsInSwing;
import megan.parsers.blast.BlastMode;
import megan.viewer.MainViewer;
import megan.viewer.TaxonomyData;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

/**
 * parses a CVS file containing a list of reads and hits
 * Daniel Huson, 9.2010
 */
public class CSVReadsHitsParser {

    /**
     * apply the importer parser to the named file.
     * Format should be:   readname,taxon,score
     *  @param fileName
     * @param doc
     */
    static public void apply(String fileName, Document doc, String[] cNames, boolean tabSeparator) throws IOException {
        System.err.println("Importing list of reads-to-CLASS hits from CSV file");
        System.err.println("Expected line format: readname,CLASS,score     - CLASS must be one of: " + Basic.toString(cNames, " name,") + " name");

        System.err.println("Using topPercent=" + doc.getTopPercent() + " minScore=" + doc.getMinScore() +
                (doc.getMinSupportPercent() > 0 ? " minSupportPercent=" + doc.getMinSupportPercent() : "") +
                " minSupport=" + doc.getMinSupport());

        final DataTable table = doc.getDataTable();
        table.clear();
        table.setCreator(ProgramProperties.getProgramName());
        table.setCreationDate((new Date()).toString());
        table.setAlgorithm(ClassificationType.Taxonomy.toString(), "Summary");

        doc.getActiveViewers().clear();
        doc.getActiveViewers().addAll(Arrays.asList(cNames));

        IdParser[] parsers = new IdParser[cNames.length];
        int taxonomyIndex = -1;
        for (int i = 0; i < cNames.length; i++) {
            String cName = cNames[i];
            if (!cName.equals(Classification.Taxonomy)) {
                parsers[i] = ClassificationManager.get(cName, true).getIdMapper().createIdParser();
                ClassificationManager.ensureTreeIsLoaded(cName);
                doc.getActiveViewers().add(cName);
            } else {
                taxonomyIndex = i;
            }
        }

        final Map<String, List<Pair<Integer, Float>>>[] readName2IdAndScore = new HashMap[cNames.length];
        for (int i = 0; i < readName2IdAndScore.length; i++) {
            readName2IdAndScore[i] = new HashMap<>();
        }

        final BufferedReader r = new BufferedReader(new FileReader(fileName));
        int numberOfErrors = 0;
        int lineNo = 0;
        String aLine;
        final int[] count = new int[parsers.length];

        while ((aLine = r.readLine()) != null) {
            lineNo++;
            aLine = aLine.trim();
            if (aLine.length() == 0 || aLine.startsWith("#"))
                continue;
            try {
                String[] tokens = aLine.split(tabSeparator ? "\t" : ",");

                if (tokens.length != 3)
                    throw new IOException("Line " + lineNo + ": incorrect number of columns, expected 3, got: " + tokens.length);


                String readName = tokens[0].trim();
                boolean found = false;
                for (int i = 0; !found && i < parsers.length; i++) {
                    int id;
                    if (i == taxonomyIndex && Basic.isInteger(tokens[1]))
                        id = Basic.parseInt(tokens[1]);
                    else
                        id = parsers[i].getIdFromHeaderLine(tokens[1]);
                    if (id != 0) {
                        float score = Float.parseFloat(tokens[2].trim());
                        List<Pair<Integer, Float>> taxonIdAndScore = readName2IdAndScore[i].get(readName);
                        if (taxonIdAndScore == null) {
                            taxonIdAndScore = new LinkedList<>();
                            readName2IdAndScore[i].put(readName, taxonIdAndScore);
                        }
                        taxonIdAndScore.add(new Pair<>(id, score));
                        count[i]++;
                        found = true;
                    }
                }
                if (!found)
                    System.err.println("Unrecognized name: " + tokens[1]);
            } catch (Exception ex) {
                System.err.println("Error: " + ex + ", skipping");
                numberOfErrors++;
            }
        }

        final int totalReads = Basic.max(count);

        if (taxonomyIndex >= 0) {
            // run LCA algorithm to get assignment of reads
            Map<Integer, Integer[]> class2counts = new HashMap<>();
            Map<Integer, Integer> class2count = new HashMap<>();

            for (String readName : readName2IdAndScore[taxonomyIndex].keySet()) {
                List<Pair<Integer, Float>> taxonIdAndScore = readName2IdAndScore[taxonomyIndex].get(readName);

                final int taxId = computeTaxonId(doc, taxonIdAndScore);

                if (taxId != 0) {
                    Integer[] counts = class2counts.get(taxId);
                    if (counts == null) {
                        counts = new Integer[]{0};
                        class2counts.put(taxId, counts);
                    }
                    counts[0]++;
                    if (class2count.get(taxId) == null)
                        class2count.put(taxId, 1);
                    else
                        class2count.put(taxId, class2count.get(taxId) + 1);
                }
            }

            // run the minsupport filter
            if (doc.getMinSupportPercent() > 0 || doc.getMinSupport() > 1) {
                if (doc.getMinSupportPercent() > 0) {
                    long assigned = 0;
                    for (int taxId : class2count.keySet()) {
                        if (taxId > 0)
                            assigned += class2count.get(taxId);
                    }
                    doc.setMinSupport((int) Math.max(1, (doc.getMinSupportPercent() / 100.0) * assigned));
                    System.err.println("MinSupport set to: " + doc.getMinSupport());
                }

                final MinSupportFilter minSupportFilter = new MinSupportFilter(Classification.Taxonomy, class2count, doc.getMinSupport(), new ProgressSilent());
                try {
                    Map<Integer, Integer> changes = minSupportFilter.apply();
                    for (Integer oldTaxId : changes.keySet()) {
                        Integer newTaxId = changes.get(oldTaxId);
                        Integer oldCount = class2counts.get(oldTaxId)[0];

                        Integer[] newCounts = class2counts.get(newTaxId);
                        if (newCounts == null || newCounts[0] == null) {
                            newCounts = new Integer[]{oldCount};
                            class2counts.put(newTaxId, newCounts);
                        } else {
                            newCounts[0] += oldCount;
                        }
                        class2counts.remove(oldTaxId);
                    }
                } catch (CanceledException e) {
                }
            }
            table.getClassification2Class2Counts().put(ClassificationType.Taxonomy.toString(), class2counts);
        } else {
            Map<Integer, Integer[]> class2counts = new HashMap<>();
            class2counts.put(IdMapper.UNASSIGNED_ID, new Integer[]{totalReads});
            table.getClassification2Class2Counts().put(ClassificationType.Taxonomy.toString(), class2counts);
        }

        for (int i = 0; i < cNames.length; i++) {
            if (i != taxonomyIndex) {
                Map<Integer, Integer[]> class2counts = new HashMap<>();
                Map<Integer, Integer> class2count = new HashMap<>();

                for (String readName : readName2IdAndScore[i].keySet()) {
                    final List<Pair<Integer, Float>> seedIdAndScore = readName2IdAndScore[i].get(readName);
                    final int seedId = getBestId(seedIdAndScore);

                    if (seedId != 0) {
                        Integer[] counts = class2counts.get(seedId);
                        if (counts == null) {
                            counts = new Integer[]{0};
                            class2counts.put(seedId, counts);
                        }
                        counts[0]++;
                        if (class2count.get(seedId) == null)
                            class2count.put(seedId, 1);
                        else
                            class2count.put(seedId, class2count.get(seedId) + 1);
                    }
                }
                table.getClassification2Class2Counts().put(cNames[i], class2counts);
            }
        }

        table.setSamples(new String[]{Basic.getFileBaseName(new File(fileName).getName())}, null, new Integer[]{totalReads}, new BlastMode[]{BlastMode.Unknown});
        table.setTotalReads(totalReads);
        doc.setNumberReads(totalReads);
        for (int i = 0; i < cNames.length; i++) {
            if (i != taxonomyIndex)
                doc.getActiveViewers().remove(cNames[i]);
        }
        if (numberOfErrors > 0)
            NotificationsInSwing.showWarning(MainViewer.getLastActiveFrame(), "Lines skipped during import: " + numberOfErrors + " (of " + lineNo + ")");
        System.err.println("done (" + totalReads + " reads)");
    }

    private static int getBestId(List<Pair<Integer, Float>> idAndScore) {
        int bestId = 0;
        float bestScore = 0;

        for (Pair<Integer, Float> pair : idAndScore) {
            if (pair.getSecond() > bestScore) {
                bestScore = pair.getSecond();
                bestId = pair.getFirst();
            }
        }
        return bestId;
    }

    /**
     * compute the taxon id for a read using the LCA algorithm
     *
     * @param doc
     * @param taxonIdAndScore
     * @return taxonId
     */
    private static int computeTaxonId(Document doc, List<Pair<Integer, Float>> taxonIdAndScore) {
        final Pair<Integer, Float>[] pairs = taxonIdAndScore.toArray((Pair<Integer, Float>[]) new Pair[taxonIdAndScore.size()]);

        // sort by decreasing bit-score:
        Arrays.sort(pairs, new Comparator<Pair<Integer, Float>>() {
            public int compare(Pair<Integer, Float> pair1, Pair<Integer, Float> pair2) {
                if (pair1.getSecond() > pair2.getSecond())
                    return -1;
                else if (pair1.getSecond() < pair2.getSecond())
                    return 1;
                else
                    return 0;
            }
        });

        Set<Integer> taxonIds = new HashSet<>();

        Float bestScore = null;

        float threshold = doc.getMinScore();

        for (Pair<Integer, Float> pair : pairs) {
            Integer taxonId = pair.getFirst();
            Float score = pair.getSecond();
            if (score >= threshold) {
                if (bestScore == null) {
                    bestScore = score;
                    taxonIds.add(taxonId);
                    if (doc.getTopPercent() != 0) {
                        threshold = Math.max((100 - doc.getTopPercent()) * bestScore, threshold);
                    }
                }
                taxonIds.add(taxonId);
            }
        }

        return TaxonomyData.getLCA(taxonIds, true);
    }
}