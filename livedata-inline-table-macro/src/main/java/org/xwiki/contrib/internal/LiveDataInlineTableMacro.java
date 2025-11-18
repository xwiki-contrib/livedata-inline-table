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

import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.cache.CacheException;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.GroupBlock;
import org.xwiki.rendering.block.MetaDataBlock;
import org.xwiki.rendering.macro.AbstractMacro;
import org.xwiki.rendering.macro.MacroContentParser;
import org.xwiki.rendering.macro.MacroExecutionException;
import org.xwiki.rendering.macro.descriptor.DefaultContentDescriptor;
import org.xwiki.rendering.parser.Parser;
import org.xwiki.rendering.renderer.BlockRenderer;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.rendering.syntax.SyntaxType;
import org.xwiki.rendering.transformation.MacroTransformationContext;
import org.xwiki.rendering.transformation.TransformationManager;
import org.xwiki.script.ScriptContextManager;
import org.xwiki.skinx.SkinExtension;
import org.xwiki.stability.Unstable;

import com.xpn.xwiki.XWikiContext;

/**
 * Macro to display a simple XWiki Syntax table with LiveData.
 * 
 * @version $Id$
 * @since 0.0.1
 */
@Component
@Named("livedata-inline-table")
@Singleton
@Unstable
public class LiveDataInlineTableMacro extends AbstractMacro<LiveDataInlineTableMacroParameters>
{

    @Inject
    @Named("ssrx")
    private SkinExtension ssrx;
    
    @Inject
    @Named("jsrx")
    private SkinExtension jsrx;
    
    @Inject
    private TransformationManager transformationManager;

    @Inject
    private MacroContentParser contentParser;

    @Inject
    @Named("plain/1.0")
    private BlockRenderer plainTextRenderer;

    @Inject
    private Provider<XWikiContext> xcontextProvider;

    @Inject
    @Named("plain/1.0")
    private Parser plainTextParser;

    @Inject
    private ComponentManager componentManager;

    @Inject
    private InlineTableCache inlineTableCache;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    @Inject
    private ScriptContextManager scriptContextManager;

    /**
     * Constructor.
     */
    public LiveDataInlineTableMacro()
    {
        super("Live Data Table Source",
            "Contains a table that can be displayed in live data. Making it filterable and sortable.",
            new DefaultContentDescriptor("Content", true, Block.LIST_BLOCK_TYPE),
            LiveDataInlineTableMacroParameters.class);
    }

    @Override
    public boolean supportsInlineMode()
    {
        return false;
    }

    @Override
    public List<Block> execute(LiveDataInlineTableMacroParameters parameters, String content,
        MacroTransformationContext context) throws MacroExecutionException
    {
        this.ssrx.use("css/livedata-inline-table-macro.css");
        this.jsrx.use("js/livedata-inline-table-macro.js");
        
        // When in WYSIWYG edit mode, it should be possible to edit underlying table using the WYSIWYG.
        // When in view mode, we should use liveData to display the table.
        XWikiContext xcontext = xcontextProvider.get();

        if (isEdit(context)) {
            return parseContent(content, context);
        }
        if ("get".equals(xcontext.getAction()) || "edit".equals(xcontext.getAction())) {
            return parseContent(content, context);
        }

        Syntax targetSyntax = context.getTransformationContext().getTargetSyntax();
        String renderSyntax = "html/5.0";
        if (targetSyntax != null) {
            renderSyntax = targetSyntax.toIdString();
        }

        try {
            return Collections.singletonList(new GroupBlock(parseReadOnlyContent(content, context))
                .clone(new LiveDataInlineTableMacroBlockFilter(parameters, context, plainTextRenderer,
                    componentManager.getInstance(BlockRenderer.class, renderSyntax), inlineTableCache.getCache(),
                    contextProvider, transformationManager, logger)));
        } catch (ComponentLookupException | LiveDataInlineTableMacroRuntimeException | CacheException e) {
            throw new MacroExecutionException(e.getMessage(), e);
        }
    }

    /**
     * Parse the content string to XDOM. This does not wrap the content in a MetaDataBlock.
     * 
     * @param content The string to parse to XDOM.
     * @param context The current transformation context.
     * @return The parsed XDOM.
     * @throws MacroExecutionException
     */
    private List<Block> parseReadOnlyContent(String content, MacroTransformationContext context)
        throws MacroExecutionException
    {
        return contentParser.parse(content, context, true, context.isInline()).getChildren();
    }

    /**
     * Parse the content string to XDOM. This wraps the content in a MetaDataBlock.
     * 
     * @param content The string to parse to XDOM.
     * @param context The current transformation context.
     * @return The parsed XDOM.
     * @throws MacroExecutionException
     */
    private List<Block> parseContent(String content, MacroTransformationContext context) throws MacroExecutionException
    {
        // Don't execute transformations explicitly. They'll be executed on the generated content later on.
        List<Block> children = contentParser.parse(content, context, false, context.isInline()).getChildren();

        return Collections.singletonList(new MetaDataBlock(children, this.getNonGeneratedContentMetaData()));
    }

    private boolean isEdit(MacroTransformationContext context)
    {
        // By default, we use the recommended solution cf:
        // https://www.xwiki.org/xwiki/bin/view/FAQ/How%20to%20write%20Macro%20code%20for%20the%20edit%20mode
        // And if we are in the version impacted by https://jira.xwiki.org/browse/XWIKI-22738
        // We fall back on the suggested solution by this comment:
        // https://jira.xwiki.org/browse/XWIKI-22738?focusedId=120587&
        //     page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-120587
        Syntax syntax = context.getTransformationContext().getTargetSyntax();
        if (syntax == null) {
            String syntaxStr = (String) scriptContextManager.getScriptContext().getAttribute("syntaxType");
            return (syntaxStr != null) && (syntaxStr.equals("annotatedhtml") || syntaxStr.equals("annotatedxhtml"));
        } else {
            SyntaxType targetSyntaxType = syntax.getType();
            return (SyntaxType.ANNOTATED_HTML.equals(targetSyntaxType)
                || SyntaxType.ANNOTATED_XHTML.equals(targetSyntaxType));
        }
    }
}
