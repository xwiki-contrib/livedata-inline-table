/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import javax.inject.Provider;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.IntegerRange;
import org.slf4j.Logger;
import org.xwiki.cache.Cache;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.BlockFilter;
import org.xwiki.rendering.block.GroupBlock;
import org.xwiki.rendering.block.MacroBlock;
import org.xwiki.rendering.block.TableBlock;
import org.xwiki.rendering.block.TableCellBlock;
import org.xwiki.rendering.block.TableHeadCellBlock;
import org.xwiki.rendering.block.TableRowBlock;
import org.xwiki.rendering.renderer.BlockRenderer;
import org.xwiki.rendering.renderer.printer.DefaultWikiPrinter;
import org.xwiki.rendering.renderer.printer.WikiPrinter;
import org.xwiki.rendering.transformation.MacroTransformationContext;
import org.xwiki.rendering.transformation.TransformationException;
import org.xwiki.rendering.transformation.TransformationManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xpn.xwiki.XWikiContext;

/**
 * Convert a Table to LiveData.
 * 
 * @version $Id$
 * @since 0.0.4
 */
public class LiveDataInlineTableMacroBlockFilter implements BlockFilter
{

    private static final String ID = "id";

    private static final String DATE = "date";

    private static final String STRING = "String";

    private static final String STYLE = "style";

    private static final String CLASS = "class";

    private static final String LIT_CELL_CLASS = "xwiki-livedata-inline-table-cell";

    private static final Pattern WIDTH_STRIPPER = Pattern.compile("(^|;)(\\s*width\\s*:[^;]*)");

    private static final String DEFAULT_FORMAT = "yyyy/MM/dd HH:mm";

    private MacroTransformationContext context;

    private BlockRenderer plainTextRenderer;

    private BlockRenderer richTextRenderer;

    private LiveDataInlineTableMacroParameters parameters;

    private Cache<String> cache;

    private Logger logger;

    private String[] dateFormats;

    private Provider<XWikiContext> contextProvider;

    private TransformationManager transformationManager;

    /**
     * Constructor.
     */
    LiveDataInlineTableMacroBlockFilter(LiveDataInlineTableMacroParameters parameters,
        MacroTransformationContext context, BlockRenderer plainTextRenderer, BlockRenderer richTextRenderer,
        Cache<String> cache, Provider<XWikiContext> contextProvider, TransformationManager transformationManager,
        Logger logger)
    {
        this.parameters = parameters;
        this.context = context;
        this.plainTextRenderer = plainTextRenderer;
        this.richTextRenderer = richTextRenderer;
        this.cache = cache;
        this.logger = logger;
        this.contextProvider = contextProvider;
        this.transformationManager = transformationManager;

        // When no DateFormats parameter is specified, use the format defined in the administration section.
        if (parameters.getDateFormats() == null || parameters.getDateFormats().isBlank()) {
            XWikiContext xcontext = contextProvider.get();
            this.dateFormats = List.of(xcontext.getWiki().getXWikiPreference("dateformat", DEFAULT_FORMAT, xcontext))
                .toArray(new String[0]);
        } else {
            this.dateFormats = parameters.getDateFormats().split(parameters.getDateFormatsSeparator());
        }

        logger.debug("Using the following date formats: " + String.join(", ", this.dateFormats));
    }

    /**
     * Checks if there is a table in the ancestors of a given block.
     * 
     * @param block the block to check for a table ancestor
     * @return true when one of the parents of block is a table, false otherwise
     */
    private boolean hasTableParent(Block block)
    {
        if (block.getParent() == null) {
            return false;
        }

        if (block.getParent() instanceof TableBlock) {
            return true;
        }

        return hasTableParent(block.getParent());
    }

    @Override
    public List<Block> filter(Block block)
    {
        if (block instanceof TableBlock && !hasTableParent(block)) {
            return transformTable((TableBlock) block);
        }

        return Collections.singletonList(block);
    }

    /**
     * The parsed representation of a table.
     * 
     * @version $Id$
     */
    private static class ParsedTable
    {
        private List<String> fields;

        private List<String> fieldsTypes;

        private List<Map<String, Object>> entries;

        /**
         * Constructor.
         * 
         * @param fields The fields of the table.
         * @param entries The entries of table.
         */
        ParsedTable(List<String> fields, List<String> fieldsTypes, List<Map<String, Object>> entries)
        {
            this.setFields(fields);
            this.setFieldsTypes(fieldsTypes);
            this.setEntries(entries);
        }

        /**
         * Gets the fields of the table.
         * 
         * @return the fields of the table.
         */
        public List<String> getFields()
        {
            return this.fields;
        }

        /**
         * Sets the fields of the table.
         * 
         * @param fields the fields of the table.
         */
        public void setFields(List<String> fields)
        {
            this.fields = fields;
        }

        /**
         * Gets the fields types of the table.
         * 
         * @return the fields types of the table.
         */
        public List<String> getFieldsTypes()
        {
            return this.fieldsTypes;
        }

        /**
         * Sets the fields types of the table.
         * 
         * @param fieldsTypes the fields of the table.
         */
        public void setFieldsTypes(List<String> fieldsTypes)
        {
            this.fieldsTypes = fieldsTypes;
        }

        /**
         * Gets the entries of the table.
         * 
         * @return the entries of the table.
         */
        public List<Map<String, Object>> getEntries()
        {
            return this.entries;
        }

        /**
         * Sets the entries of the table.
         * 
         * @param entries The entries of the table.
         */
        public void setEntries(List<Map<String, Object>> entries)
        {
            this.entries = entries;
        }
    }

    /**
     * Transform a table to LiveData.
     * 
     * @param table the Table to convert to LiveData
     * @return the new XDOM containing a LiveData.
     */
    public List<Block> transformTable(TableBlock table)
    {
        // Parse the table.
        ParsedTable parsedTable = tableToMap(table, parameters);
        List<String> fields = parsedTable.getFields();
        List<String> fieldsTypes = parsedTable.getFieldsTypes();
        List<Map<String, Object>> entries = parsedTable.getEntries();

        logger.debug("Found fields: " + String.join(",", fields.toArray(new String[0])));
        logger.debug("Fields types: " + String.join(",", fieldsTypes.toArray(new String[0])));

        // Convert the entries and fields to JSON in order to pass them to LiveData.
        String entriesJson = "";
        try {
            entriesJson = buildJSON(entries);
        } catch (JsonProcessingException e) {
            throw new LiveDataInlineTableMacroRuntimeException("Failed to serialize the table.", e);
        }

        logger.debug("Built the following entries JSON: " + entriesJson);

        // Encode the JSON to URLBase64 because it is passed to LiveData as a query parameter.
        String entriesB64 = "";
        try {
            entriesB64 = Base64.getUrlEncoder().encodeToString(compressString(entriesJson));
        } catch (IOException e) {
            throw new LiveDataInlineTableMacroRuntimeException("Failed to compress the table entries.", e);
        }

        logger.debug("Compressed and encoded the entries JSON as Base64: " + entriesB64);

        if (entriesB64.length() > 180) {
            String hash = DigestUtils.sha256Hex(entriesB64);
            logger.debug("Base64 is longer than 180 characters, storing in cache using its sha256: " + hash);
            this.cache.set(hash, entriesB64);
            entriesB64 = hash;
        }

        // Build the LiveData JSON.
        String ldJson = "";
        try {
            ldJson = buildJSON(Map.of("query",
                Map.of("properties", toArray(IntegerRange.of(0, fields.size() - 1)), "source",
                    Map.of(ID, InlineTableLiveDataSource.ID, "entries", entriesB64), "offset", 0, "limit", 10),
                "meta",
                Map.of("propertyDescriptors", getPropertyDescriptors(fields, fieldsTypes), "defaultDisplayer", "html",
                    "pagination", Map.of("showEntryRange", false, "showNextPrevious", false, "showFirstLast", false))));
        } catch (JsonProcessingException e) {
            throw new LiveDataInlineTableMacroRuntimeException("Failed to serialize the LiveData parameters.", e);
        }

        logger.debug("Built the LiveData JSON: " + ldJson);

        // Call LiveData with the computed parameters.
        String id = parameters.getId();
        Block liveDataBlock = new MacroBlock("liveData",
            id == null ? Collections.emptyMap() : Map.of(ID, parameters.getId()), ldJson, context.isInline());

        // Wrap the LiveData call in a div for styling purposes.
        Block inlineTableWrapper = new GroupBlock(Collections.singletonList(liveDataBlock),
            Collections.singletonMap("class", "livedata-inline-table_macro"));
        return Collections.singletonList(inlineTableWrapper);
    }

    private int[] toArray(IntegerRange range)
    {
        int[] array = new int[range.getMaximum() - range.getMinimum() + 1];
        for (int i = 0; i < array.length; i++) {
            array[i] = range.getMinimum() + i;
        }

        return array;
    }

    /**
     * Compress a string using GZIP.
     * 
     * @param str the string to compress
     * @return the compressed string as bytes.
     * @throws IOException
     */
    private static byte[] compressString(String str) throws IOException
    {
        if (str == null || str.length() == 0) {
            return "".getBytes(StandardCharsets.UTF_8);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(str.length());
        GZIPOutputStream gzip = new GZIPOutputStream(out);
        gzip.write(str.getBytes(StandardCharsets.UTF_8));
        gzip.close();

        return out.toByteArray();
    }

    /**
     * Generate the list of property descriptors for the given fields.
     * 
     * @param fields the name of the fields
     * @return the list of property descriptor for the given fields
     */
    private List<Object> getPropertyDescriptors(List<String> fields, List<String> fieldsTypes)
    {
        List<Object> result = new ArrayList<>();

        for (int i = 0; i < fields.size(); i++) {
            String field = fields.get(i);
            Map<String, Object> fieldMap = new HashMap<>();

            logger.debug("Setting property descriptor for field " + i + ": " + field);

            fieldMap.put(ID, "" + i);
            fieldMap.put("name", field);
            fieldMap.put("sortable", true);
            fieldMap.put("filterable", true);

            if (!fieldsTypes.get(i).equals(STRING)) {
                logger.debug(
                    "Field " + field + " has a special field type, using associated displayer: " + fieldsTypes.get(i));
                fieldMap.put("displayer", fieldsTypes.get(i));

                if (fieldsTypes.get(i).equals(DATE)) {
                    logger.debug(
                        "Field " + field + " is of type date, using html displayer and custom filter specification.");
                    fieldMap.put("displayer", "html");
                    fieldMap.put("filter", Map.of("id", "date", "dateFormat", this.dateFormats[0]));
                }
            }
            result.add(fieldMap);
        }

        return result;
    }

    /**
     * Convert a Java object to a JSON.
     * 
     * @param object
     * @return the newly constructed JSON string.
     * @throws JsonProcessingException
     */
    private String buildJSON(Object object) throws JsonProcessingException
    {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.writeValueAsString(object);
    }

    /**
     * Read a table and extract entries from it.
     * 
     * @param table The root of the table to read.
     * @param parameters The macro parameters.
     * @return The extracted list of properties and entries.
     */
    private ParsedTable tableToMap(TableBlock table, LiveDataInlineTableMacroParameters parameters)
    {
        // Extract the rows from the table while counting the number of properties.
        List<TableRowBlock> rows = new ArrayList<>();
        int propertiesCount = 0;

        for (Block child : table.getChildren()) {
            if (child instanceof TableRowBlock) {
                TableRowBlock row = (TableRowBlock) child;
                rows.add(row);
                propertiesCount = Math.max(propertiesCount, row.getChildren().size());
            }
        }
        logger.debug("Detected " + propertiesCount + " rows.");

        // Define the properties, i.e. the header of the table.
        // Define the initial fieldsTypes list.
        List<String> properties = new ArrayList<>();
        List<String> fieldsTypes = new ArrayList<>();
        for (int i = 0; i < propertiesCount; i++) {
            properties.add("" + i);
            fieldsTypes.add(null);
        }

        // Detect the fields types.
        identifyPropertiesTypes(rows, fieldsTypes, this.dateFormats);

        // Extract the entries from the rows.
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean inlineHeading = false;
        for (TableRowBlock row : rows) {
            Map<String, Object> entry = new HashMap<>();
            int i = 0;
            for (Block child : row.getChildren()) {
                if (child instanceof TableCellBlock) {
                    logger.debug("Parsing a cell of column: " + i);
                    TableCellBlock cell = (TableCellBlock) child;
                    WikiPrinter textPrinter = new DefaultWikiPrinter();

                    logger.debug("Rendering cell as text.");
                    plainTextRenderer.render(cell, textPrinter);

                    logger.debug("Rendered cell as text: " + textPrinter.toString());
                    if (entries.isEmpty() && child instanceof TableHeadCellBlock) {
                        properties.set(i, textPrinter.toString());
                        inlineHeading = true;
                        logger.debug("Detected inline heading: " + textPrinter.toString());
                    }

                    // We need to render the content of the cell as a string so that we can pass it to LiveData.
                    WikiPrinter cellPrinter = new DefaultWikiPrinter();

                    // We need to run transformations in case there is another livedata-inline-table call inside the
                    // cell.

                    // We have to fiddle with the cell's attributes...
                    Map<String, String> parsedParameters = new HashMap<>(cell.getParameters());

                    // TODO: Clean up style attribute.
                    if (parsedParameters.containsKey(STYLE)) {
                        String styleAttr = parsedParameters.get(STYLE);

                        // The strategy here is to minimally parse the CSS and remove any width property.
                        Matcher matcher = WIDTH_STRIPPER.matcher(styleAttr);

                        parsedParameters.put("STYLE", matcher.replaceAll("$1"));
                    }

                    // Update class parameter with our own class.
                    String classAttr = parsedParameters.getOrDefault(CLASS, "");
                    parsedParameters.put(CLASS, LIT_CELL_CLASS + " " + classAttr);

                    Block cellGroup = new GroupBlock(cell.getChildren(), parsedParameters);
                    logger.debug("Running cell transformations.");
                    try {
                        transformationManager.performTransformations(cellGroup,
                            this.context.getTransformationContext());
                    } catch (TransformationException e) {
                        throw new LiveDataInlineTableMacroRuntimeException("Failed to transform cell content.", e);
                    }

                    logger.debug("Rendering cell as html.");
                    richTextRenderer.render(cellGroup, cellPrinter);
                    logger.debug("Rendered cell as html: " + cellPrinter.toString());
                    entry.put("" + i, cellPrinter.toString());
                    entry.put("text." + i, textPrinter.toString());
                    if (fieldsTypes.get(i).equals(DATE)) {
                        logger.debug("A date is expected, trying to parse.");
                        String datetimeString = "";
                        Object timestamp = "";
                        if (!textPrinter.toString().isBlank()) {

                            for (int j = 0; j < this.dateFormats.length; j++) {
                                try {
                                    logger.debug("Trying to parse date using format: " + this.dateFormats[j]);

                                    Locale locale = this.contextProvider.get().getLocale();
                                    SimpleDateFormat parser = new SimpleDateFormat(this.dateFormats[j], locale);
                                    parser.setLenient(true);

                                    Date date = parser.parse(textPrinter.toString());
                                    datetimeString = new SimpleDateFormat(this.dateFormats[j], locale).format(date);
                                    timestamp = date.toInstant().getEpochSecond();
                                    logger.debug("Parsed date: " + datetimeString);
                                    logger.debug("Parsed unix timestamp: " + timestamp);
                                    entry.put("date." + i, timestamp);
                                    break;
                                } catch (ParseException e) {
                                    logger.debug("Failed to parse '" + textPrinter.toString() + "' using format "
                                        + this.dateFormats[j], e);
                                }
                            }
                        }
                    }
                    i++;
                }
            }
            entries.add(entry);
        }
        if (inlineHeading)

        {
            logger.debug("Removing the first detected row because it's a heading.");
            entries.remove(0);
        }

        return new ParsedTable(properties, fieldsTypes, entries);
    }

    private void identifyPropertiesTypes(List<TableRowBlock> rows, List<String> fieldsTypes, String[] formats)
    {

        logger.debug("Detecting the types of columns.");
        for (TableRowBlock row : rows) {
            int i = 0;
            for (Block child : row.getChildren()) {
                if (child instanceof TableCellBlock && !(child instanceof TableHeadCellBlock)) {
                    TableCellBlock cell = (TableCellBlock) child;
                    WikiPrinter textPrinter = new DefaultWikiPrinter();
                    plainTextRenderer.render(cell, textPrinter);

                    // When the plainText render is blank, do nothing.
                    if (textPrinter.toString().isBlank()) {
                        logger.debug("Plain text render of cell is empty, skipping.");
                        i++;
                        continue;
                    }

                    // When the fieldsTypes value for that column is null or "Date", parse as a date and update
                    // fieldsTypes.
                    if (fieldsTypes.get(i) == null || fieldsTypes.get(i).equals(DATE)) {
                        for (int j = 0; j < this.dateFormats.length; j++) {
                            try {
                                logger.debug("Trying to parse '" + textPrinter.toString() + "' using date format "
                                    + this.dateFormats[j]);
                                SimpleDateFormat parser =
                                    new SimpleDateFormat(this.dateFormats[j], this.contextProvider.get().getLocale());
                                parser.setLenient(true);
                                parser.parse(textPrinter.toString());
                                logger.debug("Successfully parsed date, marking column " + i + " as date.");
                                fieldsTypes.set(i, DATE);
                                break;
                            } catch (ParseException e) {
                                logger.debug("Failed to parse a date, marking column " + i + " as string.", e);
                                fieldsTypes.set(i, STRING);
                            }
                        }
                    }
                    i++;
                }
            }
        }

        logger.debug("Column type identification done.");
        for (int i = 0; i < fieldsTypes.size(); i++) {
            if (fieldsTypes.get(i) == null) {
                fieldsTypes.set(i, STRING);
            }
            logger.debug("fieldsType[" + i + "]: " + fieldsTypes.get(i));
        }
    }

}
