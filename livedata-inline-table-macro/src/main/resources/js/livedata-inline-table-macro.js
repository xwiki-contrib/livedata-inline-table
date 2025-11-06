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


require(['jquery'], function($) {
  // We want to apply our corrections whenever LiveData recreates the table layout.

  const tableCellFixer = () => {
    // We only want to work with LiveData Inline Table generated LiveData calls that use a table layout.
    const cells = $(".livedata-inline-table_macro .xwiki-livedata .layout-table .layout-table-wrapper table tr td");

    cells.toArray().forEach((rawCell) => {
      const cell = $(rawCell);
      const inlineTableCell = cell.find(".xwiki-livedata-inline-table-cell").first();
      if (inlineTableCell.length === 1) {
        const rawInlineTableCell = inlineTableCell[0];


        // Update cell's attributes.
        Array.prototype.forEach.call(rawInlineTableCell.attributes, attr => {
          if (attr.name === "data-title") {
            return;
          }

          if (attr.name === "class") {
            cell.addClass(attr.value);
            return;
          }

          cell.attr(attr.name, attr.value);
        });

        // Replace inlineTableCell with its contents.
        inlineTableCell.parent().empty().append(inlineTableCell.contents());
      }

    });
  };

  document.addEventListener("xwiki:livedata:instanceCreated", function(event) {
    tableCellFixer();
  });
  document.addEventListener("xwiki:livedata:entriesUpdated", function(event){
    tableCellFixer();
  });

});
