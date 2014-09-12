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
package org.xwiki.rendering.internal.parser.markdown;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.pegdown.ast.AutoLinkNode;
import org.pegdown.ast.ExpImageNode;
import org.pegdown.ast.ExpLinkNode;
import org.pegdown.ast.MailLinkNode;
import org.pegdown.ast.RefImageNode;
import org.pegdown.ast.RefLinkNode;
import org.pegdown.ast.ReferenceNode;
import org.pegdown.ast.WikiLinkNode;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.rendering.listener.reference.ResourceReference;
import org.xwiki.rendering.parser.ResourceReferenceParser;
import org.xwiki.rendering.renderer.reference.link.URILabelGenerator;

/**
 * Implements Pegdown Visitor's link and image events.
 *
 * @version $Id$
 * @since 4.5M1
 */
public abstract class AbstractLinkAndImagePegdownVisitor extends AbstractHTMLPegdownVisitor
{
    /**
     * HTML title attribute.
     */
    private static final String TITLE_ATTRIBUTE = "title";

    /**
     * Character in Markdown syntax to open a link.
     */
    private static final String LINK_OPEN_CHAR = "[";

    /**
     * Character in Markdown syntax to close a link.
     */
    private static final String LINK_CLOSE_CHAR = "]";

    /**
     * We parse link references with the default reference parser (i.e. the same one used by XWiki Syntax 2.1).
     */
    @Inject
    @Named("link")
    private ResourceReferenceParser linkResourceReferenceParser;

    /**
     * We parse image references with the default reference parser (i.e. the same one used by XWiki Syntax 2.1).
     */
    @Inject
    @Named("image")
    private ResourceReferenceParser imageResourceReferenceParser;

    /**
     * Used to find out at runtime a link label generator matching the link reference type.
     */
    @Inject
    private ComponentManager componentManager;

    /**
     * Link References.
     *
     * @see #visit(org.pegdown.ast.ReferenceNode)
     */
    private Map<String, ReferenceNode> references = new HashMap<String, ReferenceNode>();

    @Override
    public void visit(AutoLinkNode autoLinkNode)
    {
        ResourceReference reference = this.linkResourceReferenceParser.parse(autoLinkNode.getText());
        getListener().beginLink(reference, true, Collections.EMPTY_MAP);
        getListener().endLink(reference, true, Collections.EMPTY_MAP);
    }

    @Override
    public void visit(ExpImageNode expImageNode)
    {
        ResourceReference reference = this.imageResourceReferenceParser.parse(expImageNode.url);
        Map<String, String> parameters = new HashMap<String, String>();

        // Handle alt text. Note that in order to have the same behavior as the XWiki Syntax 2.0+ we don't add the alt
        // parameter if its content is the same as the one that would be automatically generated by the XHTML Renderer.
        String computedAltValue = computeAltAttributeValue(reference);
        String extractedAltValue = extractText(expImageNode);
        if (StringUtils.isNotEmpty(extractedAltValue) && !extractedAltValue.equals(computedAltValue)) {
            parameters.put("alt", extractedAltValue);
        }

        // Handle optional title
        addTitle(parameters, expImageNode.title);

        getListener().onImage(reference, false, parameters);
    }

    /**
     * @param reference the reference for which to compute the alt attribute value
     * @return the alt attribute value that would get generated if not specified by the user
     */
    private String computeAltAttributeValue(ResourceReference reference)
    {
        String label;
        try {
            URILabelGenerator uriLabelGenerator = this.componentManager.getInstance(URILabelGenerator.class,
                reference.getType().getScheme());
            label = uriLabelGenerator.generateLabel(reference);
        } catch (ComponentLookupException e) {
            label = reference.getReference();
        }
        return label;
    }

    @Override
    public void visit(ExpLinkNode expLinkNode)
    {
        ResourceReference reference = this.linkResourceReferenceParser.parse(expLinkNode.url);
        Map<String, String> parameters = new HashMap<String, String>();

        // Handle optional title
        addTitle(parameters, expLinkNode.title);

        getListener().beginLink(reference, false, parameters);
        visitChildren(expLinkNode);
        getListener().endLink(reference, false, parameters);
    }

    /**
     * Add a title parameter.
     *
     * @param parameters the map to which to add the title parameter
     * @param title the title parameter value to add
     */
    private void addTitle(Map<String, String> parameters, String title)
    {
        if (StringUtils.isNotEmpty(title)) {
            parameters.put(TITLE_ATTRIBUTE, title);
        }
    }

    @Override
    public void visit(MailLinkNode mailLinkNode)
    {
        ResourceReference reference = this.linkResourceReferenceParser.parse(
            "mailto:" + mailLinkNode.getText());
        getListener().beginLink(reference, true, Collections.EMPTY_MAP);
        getListener().endLink(reference, true, Collections.EMPTY_MAP);
    }

    @Override
    public void visit(ReferenceNode referenceNode)
    {
        // Since XWiki doesn't support reference links, we store reference definitions and memory and when a reference
        // is used we generate a standard link.
        this.references.put(extractText(referenceNode), referenceNode);
    }

    @Override
    public void visit(RefImageNode refImageNode)
    {
        // Since XWiki doesn't support reference images, we generate a standard image instead
        String label = extractText(refImageNode.referenceKey);

        ReferenceNode referenceNode = this.references.get(label);
        if (referenceNode != null) {
            ResourceReference reference = this.imageResourceReferenceParser.parse(referenceNode.getUrl());

            // Handle an optional link title
            Map<String, String> parameters = Collections.EMPTY_MAP;
            if (StringUtils.isNotEmpty(referenceNode.getTitle())) {
                parameters = Collections.singletonMap(TITLE_ATTRIBUTE, referenceNode.getTitle());
            }

            getListener().onImage(reference, false, parameters);
        }
    }

    @Override
    public void visit(RefLinkNode refLinkNode)
    {
        // Since XWiki doesn't support reference links, we generate a standard link instead
        // If the reference key is null then the node content is considered to be the key!
        String key;
        if (refLinkNode.referenceKey == null) {
            key = extractText(refLinkNode);
        } else {
            key = extractText(refLinkNode.referenceKey);
        }

        ReferenceNode referenceNode = this.references.get(key);
        if (referenceNode != null) {
            ResourceReference reference = this.linkResourceReferenceParser.parse(referenceNode.getUrl());

            // Handle an optional link title
            Map<String, String> parameters = Collections.EMPTY_MAP;
            if (StringUtils.isNotEmpty(referenceNode.getTitle())) {
                parameters = Collections.singletonMap(TITLE_ATTRIBUTE, referenceNode.getTitle());
            }

            getListener().beginLink(reference, false, parameters);
            visitChildren(refLinkNode);
            getListener().endLink(reference, false, parameters);
        } else {
            visit(LINK_OPEN_CHAR);
            visitChildren(refLinkNode);
            visit(LINK_CLOSE_CHAR);
            if (refLinkNode.separatorSpace != null) {
                visit(refLinkNode.separatorSpace);
                visit(LINK_OPEN_CHAR);
                if (refLinkNode.referenceKey != null) {
                    visit(key);
                }
                visit(LINK_CLOSE_CHAR);
            }
        }
    }

    @Override
    public void visit(WikiLinkNode wikiLinkNode)
    {
        ResourceReference reference = this.linkResourceReferenceParser.parse(wikiLinkNode.getText());
        getListener().beginLink(reference, false, Collections.EMPTY_MAP);
        getListener().endLink(reference, false, Collections.EMPTY_MAP);
    }
}
