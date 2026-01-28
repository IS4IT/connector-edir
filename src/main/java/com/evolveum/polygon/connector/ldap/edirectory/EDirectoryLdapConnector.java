/*
 * Copyright (c) 2015-2018 Evolveum, IS4IT
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.polygon.connector.ldap.edirectory;

import com.evolveum.polygon.common.SchemaUtil;
import com.evolveum.polygon.connector.ldap.AbstractLdapConfiguration;
import com.evolveum.polygon.connector.ldap.AbstractLdapConnector;
import com.evolveum.polygon.connector.ldap.ErrorHandler;
import com.evolveum.polygon.connector.ldap.connection.ConnectionManager;
import com.evolveum.polygon.connector.ldap.schema.AbstractSchemaTranslator;
import com.evolveum.polygon.connector.ldap.schema.LdapFilterTranslator;
import org.apache.directory.api.ldap.model.entry.DefaultModification;
import org.apache.directory.api.ldap.model.entry.Modification;
import org.apache.directory.api.ldap.model.entry.ModificationOperation;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.api.ldap.model.exception.LdapInvalidAttributeValueException;
import org.apache.directory.api.ldap.model.message.AddResponse;
import org.apache.directory.api.ldap.model.message.ModifyResponse;
import org.apache.directory.api.ldap.model.message.ResultCodeEnum;
import org.apache.directory.api.ldap.model.name.Dn;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.spi.ConnectorClass;

import java.util.*;

@ConnectorClass(displayNameKey = "connector.ldap.edirectory.display", configurationClass = EDirectoryLdapConfiguration.class)
public class EDirectoryLdapConnector extends AbstractLdapConnector<EDirectoryLdapConfiguration> {

    private static final Log LOG = Log.getLog(EDirectoryLdapConnector.class);

	/**
	 * Creates a schema translator for eDirectory LDAP connector.
	 * 
	 * @return EDirectorySchemaTranslator instance configured with schema manager and configuration
	 */
	@Override
	protected AbstractSchemaTranslator<EDirectoryLdapConfiguration> createSchemaTranslator() {
		return new EDirectorySchemaTranslator(getSchemaManager(), getConfiguration());
	}

	/**
	 * Creates an LDAP filter translator for the specified LDAP object class.
	 * 
	 * @param ldapObjectClass the LDAP object class for which to create the filter translator
	 * @return EDirectoryLdapFilterTranslator instance
	 */
	@Override
	protected LdapFilterTranslator<EDirectoryLdapConfiguration> createLdapFilterTranslator(org.apache.directory.api.ldap.model.schema.ObjectClass ldapObjectClass) {
		return new EDirectoryLdapFilterTranslator(getSchemaTranslator(), ldapObjectClass);
	}

	/**
	 * Returns the schema translator cast to EDirectorySchemaTranslator type.
	 * 
	 * @return the EDirectorySchemaTranslator instance
	 */
	@Override
	protected EDirectorySchemaTranslator getSchemaTranslator() {
		return (EDirectorySchemaTranslator)super.getSchemaTranslator();
	}
	
	/**
	 * Adds attribute modification for eDirectory-specific handling of operational attributes.
	 * Handles special processing for ENABLE, LOCK_OUT, and group membership attributes.
	 * 
	 * @param dn the distinguished name of the entry being modified
	 * @param modifications the list of modifications to append to
	 * @param ldapStructuralObjectClass the LDAP structural object class
	 * @param connIdObjectClass the ConnId object class
	 * @param delta the attribute delta containing the modification details
	 */
	@Override
	protected void addAttributeModification(Dn dn, List<Modification> modifications,
			org.apache.directory.api.ldap.model.schema.ObjectClass ldapStructuralObjectClass,
			ObjectClass connIdObjectClass, AttributeDelta delta) {
		LOG.info("Adding attribute modification for DN {0}, attribute: {1}", dn, delta.getName());
		if (delta.is(OperationalAttributes.ENABLE_NAME)) {
			Boolean enabled = Objects.requireNonNullElse(SchemaUtil.getSingleReplaceValue(delta, Boolean.class), true);
			LOG.info("Processing ENABLE attribute for DN {0}: enabled={1}", dn, enabled);
			if (enabled) {
				modifications.add(
						new DefaultModification(ModificationOperation.REPLACE_ATTRIBUTE, EDirectoryConstants.ATTRIBUTE_LOGIN_DISABLED_NAME, 
								AbstractLdapConfiguration.BOOLEAN_FALSE));
			} else {
				modifications.add(
						new DefaultModification(ModificationOperation.REPLACE_ATTRIBUTE, EDirectoryConstants.ATTRIBUTE_LOGIN_DISABLED_NAME, 
								AbstractLdapConfiguration.BOOLEAN_TRUE));
			}
		} else if (delta.is(OperationalAttributes.LOCK_OUT_NAME)) {
			LOG.info("Processing LOCK_OUT attribute for DN {0}", dn);
			Boolean lockoutValue = SchemaUtil.getSingleReplaceValue(delta, Boolean.class);
			if (lockoutValue == null) {
				lockoutValue = false;
			}
			if (lockoutValue) {
				LOG.error("Attempt to lock object at DN {0}: not supported", dn);
				throw new UnsupportedOperationException("Locking object is not supported (only unlocking is)");
			}
			modifications.add(
					new DefaultModification(ModificationOperation.REPLACE_ATTRIBUTE, EDirectoryConstants.ATTRIBUTE_LOCKOUT_LOCKED_NAME, 
							AbstractLdapConfiguration.BOOLEAN_FALSE));
			modifications.add(
					new DefaultModification(ModificationOperation.REPLACE_ATTRIBUTE, EDirectoryConstants.ATTRIBUTE_LOCKOUT_RESET_TIME_NAME)); // no value

		} else if (getSchemaTranslator().isGroupObjectClass(ldapStructuralObjectClass.getName())) {
			LOG.info("Processing group object class modification for DN {0}", dn);
			// modification handles modification of ordinary attributes - and also modification of "member" itself
			super.addAttributeModification(dn, modifications, ldapStructuralObjectClass, connIdObjectClass, delta);
			if (delta.is(getConfiguration().getGroupObjectMemberAttribute())) {
				LOG.info("Processing group member attribute modification for DN {0}", dn);
				if (getConfiguration().isManageEquivalenceAttributes()) {
					LOG.info("Managing equivalence attributes for DN {0}", dn);
					// do the same operation with a equivalentToMe attribute
					super.addAttributeModification(dn, modifications, ldapStructuralObjectClass, connIdObjectClass, 
							duplicateDelta(EDirectoryConstants.ATTRIBUTE_EQUIVALENT_TO_ME_NAME, delta));
				}
			}
		} else {
			super.addAttributeModification(dn, modifications, ldapStructuralObjectClass, connIdObjectClass, delta);
		}
	}
	
	/**
	 * Creates a duplicate of an attribute delta with a different attribute name.
	 * Copies values to replace, add, and remove operations from the original delta.
	 * 
	 * @param newAttributeName the name for the duplicated attribute delta
	 * @param origDelta the original attribute delta to duplicate
	 * @return a new AttributeDelta with the specified name and copied values
	 */
	private AttributeDelta duplicateDelta(String newAttributeName, AttributeDelta origDelta) {
		LOG.info("Duplicating delta from {0} to {1}", origDelta.getName(), newAttributeName);
		AttributeDeltaBuilder builder = new AttributeDeltaBuilder();
		builder.setName(newAttributeName);
		if (origDelta.getValuesToReplace() != null) {
			builder.addValueToReplace(origDelta.getValuesToReplace());
		}
		if (origDelta.getValuesToAdd() != null) {
			builder.addValueToAdd(origDelta.getValuesToAdd());
		}
		if (origDelta.getValuesToRemove() != null) {
			builder.addValueToRemove(origDelta.getValuesToRemove());
		}
		return builder.build();
	}

	/**
	 * Processes the result of a create (add) operation on an LDAP entry.
	 * Handles eDirectory-specific constraint violations related to password constraints.
	 * 
	 * @param dn the distinguished name of the created entry
	 * @param addResponse the response from the add operation
	 * @return a RuntimeException if an error occurred, null otherwise
	 */
	@Override
	protected RuntimeException processCreateResult(String dn, AddResponse addResponse) {
		if (addResponse.getLdapResult().getResultCode() == ResultCodeEnum.CONSTRAINT_VIOLATION &&
				addResponse.getLdapResult().getDiagnosticMessage().contains("password")) {
			LOG.error("Password constraint violation when creating LDAP entry {0}: {1}", dn, addResponse.getLdapResult().getDiagnosticMessage());
			return new InvalidAttributeValueException("Error adding LDAP entry " + dn + ": " + addResponse.getLdapResult().getDiagnosticMessage());
		}
		LOG.info("LDAP entry created successfully for DN {0}, result code: {1}", dn, addResponse.getLdapResult().getResultCode());
		return super.processCreateResult(dn, addResponse);
	}
	
	/**
	 * Processes the result of a modify operation on an LDAP entry.
	 * Handles eDirectory-specific constraint violations related to password constraints.
	 * 
	 * @param dn the distinguished name of the modified entry
	 * @param modifications the list of modifications applied
	 * @param modifyResponse the response from the modify operation
	 * @return a RuntimeException if an error occurred, null otherwise
	 */
	@Override
	protected RuntimeException processModifyResult(Dn dn, List<Modification> modifications, ModifyResponse modifyResponse) {
		if (modifyResponse.getLdapResult().getResultCode() == ResultCodeEnum.CONSTRAINT_VIOLATION &&
				modifyResponse.getLdapResult().getDiagnosticMessage().contains("password")) {
			LOG.error("Password constraint violation when modifying LDAP entry {0}, modifications: {1}, message: {2}", dn, dumpModifications(modifications), modifyResponse.getLdapResult().getDiagnosticMessage());
			return new InvalidAttributeValueException("Error modifying LDAP entry " + dn + ": " + dumpModifications(modifications) + ": " + modifyResponse.getLdapResult().getDiagnosticMessage());
		}
		LOG.info("LDAP entry modified successfully for DN {0}, result code: {1}", dn, modifyResponse.getLdapResult().getResultCode());
		return super.processModifyResult(dn, modifications, modifyResponse);
	}
	
	/**
	 * Processes an LDAP exception that occurred during a modify operation.
	 * Handles eDirectory-specific constraint violations related to password constraints.
	 * 
	 * @param dn the distinguished name of the entry being modified
	 * @param modifications the list of modifications attempted
	 * @param e the LDAP exception that occurred
	 * @return a RuntimeException representing the error
	 */
	@Override
	protected RuntimeException processModifyResult(String dn, List<Modification> modifications, LdapException e) {
		if ((e instanceof LdapInvalidAttributeValueException) && 
		((LdapInvalidAttributeValueException)e).getResultCode() == ResultCodeEnum.CONSTRAINT_VIOLATION && e.getMessage().contains("password")) {
			LOG.error("Password constraint violation exception when modifying LDAP entry {0}: {1}", dn, e.getMessage());
			return new InvalidAttributeValueException("Error modifying LDAP entry " + dn + ": " + e.getMessage(), e);
		}
		LOG.info("LDAP exception occurred when modifying entry {0}: {1}", dn, e.getMessage());
		return super.processModifyResult(dn, modifications, e);
	}

	/**
	 * Performs post-update processing for eDirectory LDAP objects.
	 * Handles reciprocal group membership attribute management for group objects.
	 * 
	 * @param connIdObjectClass the ConnId object class
	 * @param uid the unique identifier of the updated object
	 * @param deltas the set of attribute deltas applied
	 * @param options the operation options
	 * @param dn the distinguished name of the updated entry
	 * @param ldapStructuralObjectClass the LDAP structural object class
	 * @param ldapModifications the LDAP modifications applied
	 */
	@Override
	protected void postUpdate(ObjectClass connIdObjectClass, Uid uid, Set<AttributeDelta> deltas,
			OperationOptions options, 
			Dn dn, org.apache.directory.api.ldap.model.schema.ObjectClass ldapStructuralObjectClass, List<Modification> ldapModifications) {
		LOG.info("Post-update processing for DN {0}, uid: {1}", dn, uid);
		super.postUpdate(connIdObjectClass, uid, deltas, options, dn, ldapStructuralObjectClass, ldapModifications);
		if (!getConfiguration().isManageReciprocalGroupAttributes()) {
			LOG.info("Reciprocal group attributes management is disabled, skipping post-update");
			return;
		}
		if (getSchemaTranslator().isGroupObjectClass(ldapStructuralObjectClass.getName())) {
			LOG.info("Post-update processing group object class for DN {0}", dn);
			for (AttributeDelta delta: deltas) {
				if (delta.is(getConfiguration().getGroupObjectMemberAttribute())) {
					LOG.info("Updating group member attribute for DN {0}", dn);
					// this is for group of users; "members"
					if (delta.getValuesToReplace() != null) {
						LOG.error("Attempt to replace group members for DN {0}: not supported", dn);
						throw new UnsupportedOperationException("Replace of group members is not supported");
					}
					updateGroupMemberShip(dn, delta, options);
				}
				if (delta.is(getConfiguration().getGroupObjectGroupMemberAttribute())) {
					LOG.info("Updating nested group member attribute for DN {0}", dn);
					// this is for group of groups (nested); "groupMember"
					if (delta.getValuesToReplace() != null) {
						LOG.error("Attempt to replace nested group members for DN {0}: not supported", dn);
						throw new UnsupportedOperationException("Replace of group members is not supported");
					}
					updateGroupMemberShip(dn, delta, options);
				}
			}
		}
	}
	
	/**
	 * Updates group membership for a specified group by applying add and remove operations.
	 * 
	 * @param groupDn the distinguished name of the group
	 * @param delta the attribute delta containing member changes
	 * @param options the operation options
	 */
	private void updateGroupMemberShip(Dn groupDn, AttributeDelta delta, OperationOptions options) {
		LOG.info("Updating group membership for group DN {0}, attribute: {1}", groupDn, delta.getName());
		addGroupMemberShipModifications(groupDn, ModificationOperation.ADD_ATTRIBUTE, delta.getValuesToAdd(), options);
		addGroupMemberShipModifications(groupDn, ModificationOperation.REMOVE_ATTRIBUTE, delta.getValuesToRemove(), options);
	}

	/**
	 * Adds group membership modifications by updating the groupMembership attribute on member entries.
	 * 
	 * @param groupDn the distinguished name of the group
	 * @param modOp the modification operation (ADD_ATTRIBUTE or REMOVE_ATTRIBUTE)
	 * @param values the list of member DNs to add or remove
	 * @param options the operation options
	 */
	private void addGroupMemberShipModifications(Dn groupDn, ModificationOperation modOp, List<Object> values, OperationOptions options) {
		if (values == null) {
			LOG.info("No values to process for group membership modification on group {0}", groupDn);
			return;
		}
		LOG.info("Processing {0} group membership modifications for group {1}, operation: {2}", values.size(), groupDn, modOp);
		for (Object val: values) {
			Dn memberDn = getSchemaTranslator().toDn((String)val);
			LOG.info("Applying {0} operation to member DN {1}", modOp, memberDn);
			List<Modification> mods = new ArrayList<>(1);
			mods.add(new DefaultModification(modOp, EDirectoryConstants.ATTRIBUTE_GROUP_MEMBERSHIP_NAME, groupDn.toString()));
			// No need to update securityEquals. eDirectory is doing that by itself
			// (the question is why it cannot do also to the groupMembership?)
			modify(memberDn, mods, options);
		}
	}

    /**
     * Discovers eDirectory LDAP connector configuration suggestions.
     * Provides suggested values for paging strategy and other configurable properties.
     * 
     * @return a map of configuration property names to their suggested values
     */
    @Override
    public Map<String, SuggestedValues> discoverConfiguration() {
        LOG.info("Starting configuration discovery process");
        Map<String, SuggestedValues> suggestions = new HashMap<>();

        ConnectionManager<EDirectoryLdapConfiguration> connectionManager = getConnectionManager();
        // EDirectoryLdapConfiguration configuration = getConfiguration();

        LOG.info("Configuration discovery, working with root DSE:\n{0}", connectionManager.getRootDse());
/*
        // Get base contexts from Root DSE, add non-standard root context t=<TREENAME>
        if (configuration.getBaseContext() == null) {

            org.apache.directory.api.ldap.model.entry.Attribute directoryTreeName = connectionManager.getRootDseAttribute(EDirectoryConstants.ROOT_DSE_DIR_TREENAME);

            SuggestedValuesBuilder svbldr = new SuggestedValuesBuilder();
            if (directoryTreeName != null) {
                for (Value treeNameValue : directoryTreeName) {
                    svbldr.addValues("t=" + treeNameValue.getString());
                }
				suggestions.put(AbstractLdapConfiguration.CONF_PROP_NAME_BASE_CONTEXT, svbldr.buildOpen());
            }


            org.apache.directory.api.ldap.model.entry.Attribute namingContexts = connectionManager.getRootDseAttribute(SchemaConstants.NAMING_CONTEXTS_AT);
            if (namingContexts != null) {
                for (Value namingContextValue : namingContexts) {
                    svbldr.addValues(namingContextValue.getString());
                }
            }
        }
*/

		SuggestedValuesBuilder svbldr2 = new SuggestedValuesBuilder();
		svbldr2.addValues(AbstractLdapConfiguration.PAGING_STRATEGY_SPR);
		svbldr2.addValues(AbstractLdapConfiguration.PAGING_STRATEGY_VLV);
		svbldr2.addValues(AbstractLdapConfiguration.PAGING_STRATEGY_NONE);
		suggestions.put("pagingStrategy", svbldr2.build());
		LOG.info("Added paging strategy suggestions");

/*
        // Server-specific suggestions
        addServerSpecificConfigurationSuggestions(suggestions);
*/
		LOG.info("Configuration discovery completed, returning {0} suggestion groups", suggestions.size());
		return suggestions;
    }

    /**
     * Adds server-specific configuration suggestions to the provided map.
     * This method can be overridden in subclasses to provide custom suggestions.
     * 
     * @param suggestions the map to which server-specific suggestions should be added
     */
    protected void addServerSpecificConfigurationSuggestions(Map<String, SuggestedValues> suggestions) {
        // TODO: server-specific suggestions
        // Used in subclasses
    }


    /**
     * Creates an error handler for eDirectory LDAP operations.
     * 
     * @return an ErrorHandler instance, or null if no custom error handling is needed
     */
    @Override
	protected ErrorHandler createErrorHandler()
	{
		// TODO Auto-generated method stub
		return null;
	}
    
}
