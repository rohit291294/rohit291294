/*
 * Copyright (c) 2018 CA. All rights reserved.
 * This software may be modified and distributed under the terms
 * of the MIT license.  See the LICENSE file for details.
 */

package com.ca.apim.gateway.cagatewayconfig.util.gateway;

import com.ca.apim.gateway.cagatewayconfig.tasks.zip.builder.EntityBuilderException;
import com.ca.apim.gateway.cagatewayconfig.tasks.zip.bundle.loader.DependencyBundleLoadException;
import org.apache.commons.collections4.MapUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.ca.apim.gateway.cagatewayconfig.util.gateway.BundleElementNames.*;
import static com.ca.apim.gateway.cagatewayconfig.util.xml.DocumentUtils.getChildElements;
import static java.lang.Integer.parseInt;
import static java.lang.Long.parseLong;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toMap;
import static org.apache.commons.lang.BooleanUtils.toBoolean;
import static org.w3c.dom.Node.ELEMENT_NODE;

public class BuilderUtils {

    public static void buildAndAppendPropertiesElement(final Map<String, Object> properties, final Document document, final Element elementToAppendInto) {
        if (MapUtils.isEmpty(properties)) {
            return;
        }

        elementToAppendInto.appendChild(buildPropertiesElement(properties, document));
    }

    static Element buildPropertiesElement(final Map<String, Object> properties, final Document document) {
        Element propertiesElement = document.createElement(PROPERTIES);
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            Element propertyElement = document.createElement(PROPERTY);
            propertyElement.setAttribute(ATTRIBUTE_KEY, entry.getKey());
            String elementType;

            if (String.class.isAssignableFrom(entry.getValue().getClass())) {
                elementType = STRING_VALUE;
            } else if (Integer.class.isAssignableFrom(entry.getValue().getClass())) {
                elementType = INT_VALUE;
            } else if (Long.class.isAssignableFrom(entry.getValue().getClass())) {
                elementType = LONG_VALUE;
            } else if (Boolean.class.isAssignableFrom(entry.getValue().getClass())) {
                elementType = BOOLEAN_VALUE;
            } else {
                throw new EntityBuilderException("Could not create property (" + entry.getKey() + ") for value type: " + entry.getValue().getClass().getTypeName());
            }

            Element valueElement = document.createElement(elementType);
            valueElement.setTextContent(entry.getValue().toString());
            propertyElement.appendChild(valueElement);
            propertiesElement.appendChild(propertyElement);
        }
        return propertiesElement;
    }

    /**
     * Map a l7:Properties element values into a Map of key-value objects.
     *
     * @param propertiesElement properties element of bundle (l7:Properties)
     * @return map of properties found into element, empty if null or no properties
     * @throws DependencyBundleLoadException if node is not l7:Properties, if there is any l7:Property without any l7:xxxValue and if the l7:xxxValue is not yet supported.
     */
    public static Map<String, Object> mapPropertiesElements(final Element propertiesElement) {
        if (propertiesElement == null) {
            return emptyMap();
        }

        if (!Objects.equals(propertiesElement.getNodeName(), PROPERTIES)) {
            throw new DependencyBundleLoadException("Current node is not " + PROPERTIES + " node, it is " + propertiesElement.getNodeName());
        }

        final List<Element> properties = getChildElements(propertiesElement, PROPERTY);
        return properties.stream().collect(toMap(s -> s.getAttribute(ATTRIBUTE_KEY), o -> {
            final String propKey = o.getAttribute(ATTRIBUTE_KEY);
            final NodeList childNodes = o.getChildNodes();

            for (int i = 0; i < childNodes.getLength(); i++) {
                final Node child = childNodes.item(i);
                if (child.getNodeType() == ELEMENT_NODE) {
                    return extractPropertyValue(propKey, (Element) child);
                }
            }

            throw new DependencyBundleLoadException("Property " + propKey + " does not have a value");
        }));
    }

    private static Object extractPropertyValue(final String key, final Element valueElement) {
        switch (valueElement.getNodeName()) {
            case STRING_VALUE: return valueElement.getTextContent();
            case BOOLEAN_VALUE: return toBoolean(valueElement.getTextContent());
            case LONG_VALUE: return parseLong(valueElement.getTextContent());
            case INT_VALUE: return parseInt(valueElement.getTextContent());
            default:
                throw new DependencyBundleLoadException("Type of property " + key + " is " + valueElement.getNodeName() + " which is not yet supported");
        }
    }

    private BuilderUtils() {
    }
}