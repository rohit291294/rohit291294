/*
 * Copyright (c) 2018 CA. All rights reserved.
 * This software may be modified and distributed under the terms
 * of the MIT license.  See the LICENSE file for details.
 */

package com.ca.apim.gateway.cagatewayexport.util.policy;

import com.ca.apim.gateway.cagatewayconfig.beans.Bundle;
import com.ca.apim.gateway.cagatewayconfig.beans.Encass;
import com.ca.apim.gateway.cagatewayconfig.beans.Policy;
import com.ca.apim.gateway.cagatewayconfig.util.xml.DocumentParseException;
import org.w3c.dom.Element;

import javax.inject.Singleton;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.ca.apim.gateway.cagatewayconfig.util.policy.PolicyXMLElements.*;
import static com.ca.apim.gateway.cagatewayconfig.util.xml.DocumentUtils.getSingleElement;

/**
 * Simplifier for encass elements.
 */
@Singleton
public class EncapsulatedAssertionSimplifier implements PolicyAssertionSimplifier {

    private static final Logger LOGGER = Logger.getLogger(EncapsulatedAssertionSimplifier.class.getName());

    @Override
    public void simplifyAssertionElement(PolicySimplifierContext context) throws DocumentParseException {
        Element encapsulatedAssertionElement = context.getAssertionElement();
        Bundle bundle = context.getBundle();

        Element encassGuidElement = getSingleElement(encapsulatedAssertionElement, ENCAPSULATED_ASSERTION_CONFIG_GUID);
        String encassGuid = encassGuidElement.getAttribute(STRING_VALUE);
        Optional<Encass> encassEntity = bundle.getEntities(Encass.class).values().stream().filter(e -> encassGuid.equals(e.getGuid())).findAny();
        if (encassEntity.isPresent()) {
            Policy policyEntity = bundle.getPolicies().values().stream().filter(p -> encassEntity.get().getPolicyId().equals(p.getId())).findFirst().orElse(null);
            if (policyEntity != null) {
                encapsulatedAssertionElement.setAttribute("encassName", encassEntity.get().getName());
                Element encapsulatedAssertionConfigNameElement = getSingleElement(encapsulatedAssertionElement, ENCAPSULATED_ASSERTION_CONFIG_NAME);
                encapsulatedAssertionElement.removeChild(encapsulatedAssertionConfigNameElement);
                encapsulatedAssertionElement.removeChild(encassGuidElement);
            } else {
                LOGGER.log(Level.WARNING, "Could not find referenced encass policy with id: {0}", encassEntity.get().getPolicyId());
            }
        } else {
            LOGGER.log(Level.WARNING, "Could not find referenced encass with guid: {0}", encassGuid);
        }
    }

    @Override
    public String getAssertionTagName() {
        return ENCAPSULATED;
    }
}