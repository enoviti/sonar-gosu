/*
 * Sonar Gosu Plugin
 * Copyright (C) 2016-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.gosu.cobertura;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.apache.commons.lang.StringUtils;
import org.codehaus.staxmate.in.SMInputCursor;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.coverage.CoverageType;
import org.sonar.api.batch.sensor.coverage.NewCoverage;
import org.sonar.api.utils.MessageException;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.plugins.gosu.foundation.Gosu;
import org.sonar.plugins.gosu.utils.StaxParser;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import javax.xml.stream.XMLStreamException;

import java.io.File;
import java.text.ParseException;
import java.util.List;
import java.util.Map;

import static java.util.Locale.ENGLISH;
import static org.sonar.api.utils.ParsingUtils.parseNumber;

public class CoberturaReportParser {

  private static final Logger LOG = Loggers.get(CoberturaReportParser.class);

  private final SensorContext context;
  private final FileSystem fileSystem;
  private List<String> sourceDirs = Lists.newArrayList();

  public CoberturaReportParser(SensorContext context, final FileSystem fileSystem) {
    this.context = context;
    this.fileSystem = fileSystem;
  }

  /**
   * Parse a Cobertura xml report and create measures accordingly
   */
  public void parseReport(File xmlFile) {
    try {
      parseSources(xmlFile);
      parsePackages(xmlFile);
    } catch (XMLStreamException e) {
      throw MessageException.of("Unable to parse Cobertura report.", e);
    }
  }

  private void parseSources(File xmlFile) throws XMLStreamException {
    StaxParser sourceParser = new StaxParser(rootCursor -> {
      rootCursor.advance();
      sourceDirs = collectSourceDirs(rootCursor.descendantElementCursor("source"));
    });
    sourceParser.parse(xmlFile);
  }

  private static List<String> collectSourceDirs(SMInputCursor source) throws XMLStreamException {
    List<String> directories = Lists.newLinkedList();
    while (source.getNext() != null) {
      String sourceDir = cleanSourceDir(source.getElemStringValue());
      if (StringUtils.isNotBlank(sourceDir)) {
        directories.add(sourceDir);
      }
    }
    return directories;
  }

  private static String cleanSourceDir(String sourceDir) {
    if (StringUtils.isNotBlank(sourceDir)) {
      return sourceDir.trim();
    }
    return sourceDir;
  }

  private void parsePackages(File xmlFile) throws XMLStreamException {
    StaxParser fileParser = new StaxParser(rootCursor -> {
      rootCursor.advance();
      collectPackageMeasures(rootCursor.descendantElementCursor("package"));
    });
    fileParser.parse(xmlFile);
  }

  private void collectPackageMeasures(SMInputCursor pack) throws XMLStreamException {
    while (pack.getNext() != null) {
      Map<String, ParsingResult> resultByFilename = Maps.newHashMap();
      collectFileMeasures(pack.descendantElementCursor("class"), resultByFilename);
      handleFileMeasures(resultByFilename);
    }
  }

  private static void handleFileMeasures(Map<String, ParsingResult> resultByFilename) {
    for (ParsingResult parsingResult : resultByFilename.values()) {
      if (parsingResult.inputFile != null && Gosu.KEY.equals(parsingResult.inputFile.language())) {
        parsingResult.coverage.save();
      } else {
        LOG.warn("File not found: {}", parsingResult.filename);
      }
    }
  }

  @CheckForNull
  private InputFile getInputFile(String filename, List<String> sourceDirs) {
    for (String sourceDir : sourceDirs) {
      String fileAbsolutePath = sourceDir + "/" + filename;
      InputFile file = fileSystem.inputFile(fileSystem.predicates().hasAbsolutePath(fileAbsolutePath));
      if (file != null) {
        return file;
      }
    }
    return null;
  }

  private void collectFileMeasures(SMInputCursor clazz, Map<String, ParsingResult> resultByFilename)
          throws XMLStreamException {
    while (clazz.getNext() != null) {
      String fileName = clazz.getAttrValue("filename");
      ParsingResult parsingResult = resultByFilename.get(fileName);
      if (parsingResult == null) {
        InputFile inputFile = getInputFile(fileName, sourceDirs);
        NewCoverage onFile = context.newCoverage().onFile(inputFile).ofType(CoverageType.UNIT);
        parsingResult = new ParsingResult(fileName, inputFile, onFile);
        resultByFilename.put(fileName, parsingResult);
      }
      collectFileData(clazz, parsingResult);
    }
  }

  private static void collectFileData(SMInputCursor clazz, ParsingResult parsingResult) throws XMLStreamException {
    SMInputCursor line = clazz.childElementCursor("lines").advance().childElementCursor("line");
    while (line.getNext() != null) {
      int lineId = Integer.parseInt(line.getAttrValue("number"));
      boolean validLine = parsingResult.isValidLine(lineId);
      if (!validLine && parsingResult.fileExists()) {
        LOG.info("Hit on invalid line for file " + parsingResult.filename + " (line: " + lineId + "/" + parsingResult.inputFile.lines() + ")");
      }
      try {
        int hits = (int) parseNumber(line.getAttrValue("hits"), ENGLISH);
        if (validLine) {
          parsingResult.coverage = parsingResult.coverage.lineHits(lineId, hits);
        }
      } catch (ParseException e) {
        throw MessageException.of("Unable to parse Cobertura report.", e);
      }

      String isBranch = line.getAttrValue("branch");
      String text = line.getAttrValue("condition-coverage");
      if (validLine && StringUtils.equals(isBranch, "true") && StringUtils.isNotBlank(text)) {
        String[] conditions = StringUtils.split(StringUtils.substringBetween(text, "(", ")"), "/");
        parsingResult.coverage = parsingResult.coverage.conditions(lineId, Integer.parseInt(conditions[1]), Integer.parseInt(conditions[0]));
      }
    }
  }

  private static class ParsingResult {
    private final String filename;
    @Nullable
    private final InputFile inputFile;
    private NewCoverage coverage;

    public ParsingResult(String filename, @Nullable InputFile inputFile, NewCoverage coverage) {
      this.filename = filename;
      this.inputFile = inputFile;
      this.coverage = coverage;
    }

    public boolean isValidLine(int lineId) {
      return fileExists() && lineId> 0 && lineId <= inputFile.lines();
    }

    public boolean fileExists() {
      return inputFile != null;
    }
  }
}
