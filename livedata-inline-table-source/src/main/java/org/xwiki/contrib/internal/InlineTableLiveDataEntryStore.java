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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.xwiki.livedata.LiveData;
import org.xwiki.livedata.LiveDataEntryStore;
import org.xwiki.livedata.LiveDataException;
import org.xwiki.livedata.LiveDataQuery;
import org.xwiki.livedata.LiveDataQuery.Constraint;
import org.xwiki.livedata.LiveDataQuery.Filter;
import org.xwiki.text.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dedicated {@link LiveDataEntryStore} for the {@link InlineTableLiveDataSource}. This component reads the JSON sent by
 * the LiveData user and sends its contents back through LiveData.
 * 
 * @version $Id$
 * @since 0.0.1
 */
public class InlineTableLiveDataEntryStore implements LiveDataEntryStore
{
    private InlineTableLiveDataSource liveDataTableSource;

    /**
     * Constructor.
     * 
     * @param liveDataTableSource The calling {@link InlineTableLiveDataSource} used for context dependent properties.
     */
    public InlineTableLiveDataEntryStore(InlineTableLiveDataSource liveDataTableSource)
    {
        super();
        this.liveDataTableSource = liveDataTableSource;
    }

    @Override
    public Optional<Map<String, Object>> get(Object entryId) throws LiveDataException
    {
        return Optional.empty();
    }

    @Override
    public LiveData get(LiveDataQuery query) throws LiveDataException
    {
        LiveData liveData = new LiveData();
        List<Map<String, Object>> liveDataEntries = liveData.getEntries();

        // Decode the received entries.
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode entriesNode = null;
        try {
            String decodedJson = new String(
                Base64.getUrlDecoder().decode(this.liveDataTableSource.getParameters().get("entries").toString()),
                StandardCharsets.UTF_8);
            entriesNode = objectMapper.readTree(decodedJson);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        // Organize the filter so we can access them by field.
        Map<String, Filter> filters = new HashMap<>();
        for (Filter filter : query.getFilters()) {
            filters.put(filter.getProperty(), filter);
        }

        // Parse the decoded JSON.
        for (JsonNode entry : entriesNode) {
            Map<String, Object> ldEntry = new HashMap<>();

            // Keep track of whether we should reject this entry due to filtering.
            boolean filtered = false;

            // Iterate through the fields of this entry.
            for (Iterator<String> it = entry.fieldNames(); it.hasNext();) {
                String field = it.next();

                String value = entry.get(field).asText();

                // Check the filters for this field.
                Filter filter = filters.get(field);
                if (filter != null) {
                    for (Constraint constraint : filter.getConstraints()) {
                        switch (constraint.getOperator()) {
                            case "startsWith":
                                if (!StringUtils.startsWithIgnoreCase(value, constraint.getValue().toString())) {
                                    filtered = true;
                                }
                                constraint.getValue();
                                break;
                            case "equals":
                                if (!value.equals(constraint.getValue().toString())) {
                                    filtered = true;
                                }
                                break;
                            // We consider "contains" to be the default operator.
                            default:
                                if (!StringUtils.containsIgnoreCase(value, constraint.getValue().toString())) {
                                    filtered = true;
                                }
                        }
                    }
                }

                // Add the field to the entry.
                ldEntry.put(field, value);
            }
            // Add the entry to the response.
            if (!filtered) {
                liveDataEntries.add(ldEntry);
            }
        }

        liveData.setCount(liveDataEntries.size());
        return liveData;
    }

}
