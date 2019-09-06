/*
 * Copyright (C) 2018 European Spallation Source ERIC.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.phoebus.service.saveandrestore.persistence.dao.impl;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.transaction.annotation.Transactional;

import org.phoebus.applications.saveandrestore.model.ConfigPv;
import org.phoebus.applications.saveandrestore.model.Node;
import org.phoebus.applications.saveandrestore.model.NodeType;
import org.phoebus.applications.saveandrestore.model.SnapshotItem;
import org.phoebus.service.saveandrestore.model.internal.SnapshotPv;
import org.phoebus.service.saveandrestore.persistence.dao.NodeDAO;
import org.phoebus.service.saveandrestore.persistence.dao.SnapshotDataConverter;
import org.phoebus.service.saveandrestore.services.exception.NodeNotFoundException;
import org.phoebus.service.saveandrestore.services.exception.SnapshotNotFoundException;

/**
 * @author georgweiss
 * Created 11 Mar 2019
 */
public class NodeJdbcDAO implements NodeDAO {

	@Autowired
	private SimpleJdbcInsert configurationEntryInsert;

	@Autowired
	private SimpleJdbcInsert configurationEntryRelationInsert;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SimpleJdbcInsert nodeInsert;

	@Autowired
	private SimpleJdbcInsert snapshotPvInsert;
	
	
	private Node getParentNode(int nodeId) {

		// Root folder is its own parent
		if (nodeId == Node.ROOT_NODE_ID) {
			return getNode(Node.ROOT_NODE_ID);
		}

		try {
			int parentNodeId = jdbcTemplate.queryForObject(
					"select ancestor from node_closure where descendant=? and depth=1", new Object[] { nodeId },
					Integer.class);
			return getNode(parentNodeId);
		} catch (DataAccessException e) {
			return null;
		}
	}
	
	@Override
	public Node getParentNode(String uniqueNodeId) {
		int nodeId;
		try {
			nodeId = jdbcTemplate.queryForObject("select id from node where unique_id=?", new Object[] {uniqueNodeId}, Integer.class);
			return getParentNode(nodeId);
		} catch (DataAccessException e) {
			return null;
		}
	
	}
	
	/**
	 * Retrieves a {@link Node} associated with the specified node id. 
	 * 
	 * @param nodeId The node id.
	 * @return <code>null</code> if the node id is not found, otherwise either a
	 *         {@link Node} object.
	 */
	private Node getNode(int nodeId) {

		try {
			Node node = jdbcTemplate.queryForObject("select * from node where id=?", new Object[] { nodeId },
					new NodeRowMapper());
			node.setProperties(getProperties(node.getId()));
			return node;
		} catch (DataAccessException e) {
			return null;
		}
	}
	
	/**
	 * Retrieves a {@link Node} associated with the specified node id. 
	 * 
	 * @param uniqueNodeId The node id.
	 * @return <code>null</code> if the node id is not found, otherwise either a
	 *         {@link Node} object.
	 */
	@Transactional
	@Override
	public Node getNode(String uniqueNodeId) {

		int nodeId;
		try {
			nodeId = jdbcTemplate.queryForObject("select id from node where unique_id=?", new Object[] {uniqueNodeId}, Integer.class);
			return getNode(nodeId);
		} catch (DataAccessException e) {
			return null;
		}
	}

	@Override
	@Transactional
	public List<Node> getChildNodes(String uniqueNodeId) {

		Node parentNode = getNode(uniqueNodeId);
		// Node may have been deleted -> client's data is out-of-sync with server's data
		if(parentNode == null) {
			throw new NodeNotFoundException(String.format("Cannot get child nodes of unique id %s as it does not exist", uniqueNodeId));
		}
		return getChildNodes(parentNode.getId());
	}
	
	private List<Node> getChildNodes(int nodeId){
		List<Node> childNodes = jdbcTemplate.query("select n.* from node as n join node_closure as nc on n.id=nc.descendant where "
				+ "nc.ancestor=? and nc.depth=1", new Object[] { nodeId }, new NodeRowMapper());
		childNodes.forEach(childNode -> childNode.setProperties(getProperties(childNode.getId())));
		
		return childNodes;
		
	}

	@Transactional
	@Override
	public void deleteNode(String uniqueNodeId) {
		
		if(uniqueNodeId == null || uniqueNodeId.isEmpty()) {
			throw new IllegalArgumentException(String.format("Cannot delete node, invalid unique node id specified:%s", uniqueNodeId));
		}

		Node nodeToDelete = getNode(uniqueNodeId);
		if(nodeToDelete == null) {
			throw new NodeNotFoundException(String.format("Node with id=%s not found", uniqueNodeId));
		}
		if (nodeToDelete.getId() == Node.ROOT_NODE_ID) {
			throw new IllegalArgumentException("Root node cannot be deleted");
		}
		
		Node parent = getParentNode(nodeToDelete.getId());
		
		if(parent == null) {
			throw new IllegalArgumentException(String.format("Parent node of node id=%d cannot be found. Should not happen!", nodeToDelete.getId()));
		}
		
		if (nodeToDelete.getNodeType().equals(NodeType.CONFIGURATION)) {
			List<Integer> configPvIds = jdbcTemplate.queryForList(
					"select config_pv_id from config_pv_relation where config_id=?", new Object[] { nodeToDelete.getId() },
					Integer.class);
			deleteOrphanedPVs(configPvIds);
			for (Node node : getChildNodes(nodeToDelete.getUniqueId())) {
				deleteNode(node.getUniqueId());
			}
		} else if (nodeToDelete.getNodeType().equals(NodeType.FOLDER)) {
			for (Node node : getChildNodes(nodeToDelete.getUniqueId())) {
				deleteNode(node.getUniqueId());
			}
		} else if(nodeToDelete.getNodeType().equals(NodeType.SNAPSHOT)) {
			jdbcTemplate.update("delete from snapshot_node_pv where snapshot_node_id=?", nodeToDelete.getId());
		}

		jdbcTemplate.update("delete from node where unique_id=?", uniqueNodeId);
		
		
		
		// Update last modified date of the parent node
		jdbcTemplate.update("update node set last_modified=? where id=?", Timestamp.from(Instant.now()), parent.getId());
	}

	/**
	 * Creates a new node in the tree. An {@link IllegalArgumentException} is thrown if:
	 * <ul>
	 * <li>The <code>parentsUniqueId</code> argument is null or identifies a non-existing node</li>
	 * <li>If the node's type is {@link NodeType#FOLDER} or {@link NodeType#CONFIGURATION} and the parent node is not of type {@link NodeType#FOLDER}.</li>
	 * <li>If the node's type is {@link NodeType#SNAPSHOT} and the parent node is not of type {@link NodeType#CONFIGURATION}.</li>
	 * <li>If the parent node already contains a child node of the same type and name.</li>
	 * </ul>
	 */
	@Transactional
	@Override
	public Node createNode(String parentsUniqueId, final Node node) {
		if(parentsUniqueId == null) {
			throw new IllegalArgumentException("Cannot create node, parent unique id not specified.");
		}

		Node parentNode = getNode(parentsUniqueId);

		if (parentNode == null) {
			throw new IllegalArgumentException(
					String.format("Cannot create new node as parent unique_id=%s does not exist.", parentsUniqueId));
		}

		// Folder and Config can only be created in a Folder
		if ((node.getNodeType().equals(NodeType.FOLDER) || node.getNodeType().equals(NodeType.CONFIGURATION))
				&& !parentNode.getNodeType().equals(NodeType.FOLDER)) {
			throw new IllegalArgumentException(
					"Parent node is not a folder, cannot create new node of type " + node.getNodeType());
		}
		// Snapshot can only be created in Config
		if (node.getNodeType().equals(NodeType.SNAPSHOT) && !parentNode.getNodeType().equals(NodeType.CONFIGURATION)) {
			throw new IllegalArgumentException("Parent node is not a configuration, cannot create snapshot");
		}

		// The node to be created cannot have same name and type as any of the parent's
		// child nodes
		List<Node> parentsChildNodes = getChildNodes(parentNode.getUniqueId());
		
		if (!isNodeNameValid(node, parentsChildNodes)) {
			throw new IllegalArgumentException("Node of same name and type already exists in parent node.");
		}

		Timestamp now = Timestamp.from(Instant.now());
		String uniqueId = node.getUniqueId() == null ? UUID.randomUUID().toString() : node.getUniqueId();

		Map<String, Object> params = new HashMap<>(2);
		params.put("type", node.getNodeType().toString());
		params.put("created", now);
		params.put("last_modified", now);
		params.put("unique_id", uniqueId);
		params.put("name",
				node.getNodeType().equals(NodeType.SNAPSHOT)
						? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(now)
						: node.getName());
		params.put("username", node.getUserName());

		int newNodeId = nodeInsert.executeAndReturnKey(params).intValue();

		jdbcTemplate.update(
				"insert into node_closure (ancestor, descendant, depth) " + "select t.ancestor, " + newNodeId
						+ ", t.depth + 1  from node_closure as t where t.descendant = ? union all select ?, ?, 0",
				parentNode.getId(), newNodeId, newNodeId);

		// Update the last modified date of the parent folder
		jdbcTemplate.update("update node set last_modified=? where id=?", Timestamp.from(Instant.now()),
				parentNode.getId());
		
		updateProperties(newNodeId, node.getProperties());

		return getNode(uniqueId);
	}

	@Override
	@Transactional
	public List<ConfigPv> getConfigPvs(String configUniqueNodeId){
		return jdbcTemplate.query("select * from config_pv "
				+ "join config_pv_relation on config_pv.id=config_pv_relation.config_pv_id "
				+ "join node on node.id=config_pv_relation.config_id "
				+ "where config_pv_relation.config_id=(select id from node where unique_id=?)",
				new Object[] { configUniqueNodeId }, new ConfigPvRowMapper());
	}

	private boolean isNodeNameValid(Node nodeToCheck, List<Node> parentsChildNodes) {
		for (Node node : parentsChildNodes) {
			if (node.getName().equals(nodeToCheck.getName()) && 
					node.getNodeType().equals(nodeToCheck.getNodeType())) {
				return false;
			}
		}
		return true;
	}

	@Override
	public Node getRootNode() {
		return getNode(Node.ROOT_NODE_ID);
	}


	private void saveConfigPv(int nodeId, ConfigPv configPv) {
		
		List<Integer> list;
		
		if(configPv.getReadbackPvName() == null) { 
			list = jdbcTemplate.queryForList("select id from config_pv where name=? and readback_name is NULL", 
					new Object[] { configPv.getPvName() }, Integer.class);
		}
		else {
			list = jdbcTemplate.queryForList("select id from config_pv where name=? and readback_name=?", 
					new Object[] { configPv.getPvName(), configPv.getReadbackPvName()}, Integer.class);
		}

		int configPvId = 0;

		if (!list.isEmpty()) {
			configPvId = list.get(0);
		} else {
			Map<String, Object> params = new HashMap<>(4);
			params.put("name", configPv.getPvName().trim());
			params.put("readback_name", configPv.getReadbackPvName());
			params.put("readonly", configPv.isReadOnly());
			configPvId = configurationEntryInsert.executeAndReturnKey(params).intValue();
		}

		Map<String, Object> params = new HashMap<>(2);
		params.put("config_id", nodeId);
		params.put("config_pv_id", configPvId);

		configurationEntryRelationInsert.execute(params);
	}


	private void deleteOrphanedPVs(Collection<Integer> pvList) {
		for (Integer pvId : pvList) {
			int count = jdbcTemplate.queryForObject("select count(*) from config_pv_relation where config_pv_id=?",
					new Object[] { pvId }, Integer.class);

			if (count == 0) {
				jdbcTemplate.update("delete from config_pv where id=?", pvId);
			}
		}
	}

	@Override
	@Transactional
	public Node moveNode(String uniqueNodeId, String targetUniqueNodeId, String userName) {

		Node sourceNode = getNode(uniqueNodeId);

		if (sourceNode == null) {
			throw new NodeNotFoundException(String.format("Source node with unqiue id=%s not found", uniqueNodeId));
		}
		
		if(!sourceNode.getNodeType().equals(NodeType.FOLDER) && !sourceNode.getNodeType().equals(NodeType.CONFIGURATION)) {
			throw new IllegalArgumentException(String.format("Moving node of type %s not supported", NodeType.SNAPSHOT.toString()));
		}

		Node targetNode = getNode(targetUniqueNodeId);
		if(targetNode == null || !targetNode.getNodeType().equals(NodeType.FOLDER)) {
			throw new IllegalArgumentException("Target node does not exist or is not a folder");
		}
		
		List<Node> parentsChildNodes = getChildNodes(targetNode.getId());
		if (!isNodeNameValid(sourceNode, parentsChildNodes)) {
			throw new IllegalArgumentException("Node of same name and type already exists in target node.");
		}

		jdbcTemplate.update("delete from node_closure where "
				+ "descendant in (select descendant from node_closure where ancestor=?) "
				+ "and ancestor in (select ancestor from node_closure where descendant=? and ancestor != descendant)",
				sourceNode.getId(), sourceNode.getId());

		jdbcTemplate.update("insert into node_closure (ancestor, descendant, depth) "
				+ "select supertree.ancestor, subtree.descendant, supertree.depth + subtree.depth + 1 AS depth "
				+ "from node_closure as supertree " + "cross join node_closure as subtree "
				+ "where supertree.descendant=? and subtree.ancestor=?", targetNode.getId(), sourceNode.getId());

		// Update the last modified date of the source and target folder.
		jdbcTemplate.update("update node set last_modified=?, username=? where id=? or id=?",
				Timestamp.from(Instant.now()), userName, targetNode.getId(), sourceNode.getId());

		return getNode(targetNode.getId());
	}

	@Override
	@Transactional
	public Node updateConfiguration(Node configToUpdate, List<ConfigPv> updatedConfigPvList) {

		Node configNode = getNode(configToUpdate.getUniqueId());
		
		if(configNode == null || !configNode.getNodeType().equals(NodeType.CONFIGURATION)) {
			throw new IllegalArgumentException(String.format("Config node with unique id=%s not found or is wrong type", configToUpdate.getUniqueId()));
		}

		List<ConfigPv> existingConfigPvList = getConfigPvs(configToUpdate.getUniqueId());
		
		Collection<ConfigPv> pvsToRemove = CollectionUtils.removeAll(existingConfigPvList,
				updatedConfigPvList);
		Collection<Integer> pvIdsToRemove = CollectionUtils.collect(pvsToRemove, ConfigPv::getId);

		// Remove PVs from relation table
		pvIdsToRemove.stream().forEach(id -> jdbcTemplate.update(
				"delete from config_pv_relation where config_id=? and config_pv_id=?", 
				configNode.getId(), id));

		// Check if any of the PVs is orphaned
		deleteOrphanedPVs(pvIdsToRemove);

		Collection<ConfigPv> pvsToAdd = CollectionUtils.removeAll(updatedConfigPvList,
				existingConfigPvList);

		// Add new PVs
		pvsToAdd.stream().forEach(configPv -> saveConfigPv(configNode.getId(), configPv));

		updateProperties(configNode.getId(), configToUpdate.getProperties());
		
		jdbcTemplate.update("update node set username=?, last_modified=? where id=?", 
				configToUpdate.getUserName(), Timestamp.from(Instant.now()), configNode.getId());

		return getNode(configNode.getId());
	}

	@Transactional
	@Override
	public Node updateNode(Node nodeToUpdate) {
		
		Node persistedNode = getNode(nodeToUpdate.getUniqueId());
		
		if(persistedNode == null) {
			throw new IllegalArgumentException(String.format("Node with unique id=%s not found", nodeToUpdate.getUniqueId()));
		}

		if (persistedNode.getId() == Node.ROOT_NODE_ID) {
			throw new IllegalArgumentException("Cannot update root node");
		}
		
		if(!persistedNode.getNodeType().equals(nodeToUpdate.getNodeType())) {
			throw new IllegalArgumentException("Changing node type is not supported");
		}
		
		Node parentNode = getParentNode(persistedNode.getId());
		
		if(parentNode == null) {
			throw new IllegalArgumentException(
					String.format("Cannot update node id=%d as its parent node is not found. Should not happen!", persistedNode.getId()));
		}
		
		List<Node> parentsChildNodes = getChildNodes(parentNode.getId());
		
		if(!nodeToUpdate.getName().equals(persistedNode.getName()) && !isNodeNameValid(nodeToUpdate, parentsChildNodes)) {
			throw new IllegalArgumentException(String.format("A node with same type and name (%s) already exists in the same folder", nodeToUpdate.getName()));
		}
		
		jdbcTemplate.update("update node set name=?, last_modified=?, username=? where id=?", 
				nodeToUpdate.getName(), Timestamp.from(Instant.now()), nodeToUpdate.getUserName(), persistedNode.getId());
		
	
		updateProperties(persistedNode.getId(), nodeToUpdate.getProperties());
		
		return getNode(persistedNode.getId());
	}
	

	@Transactional
	@Override
	public void commitSnapshot(String uniqueNodeId, String snapshotName, String userName, String comment) {
		
		Node snapshotNode = getNode(uniqueNodeId);
		
		if(snapshotNode == null) {
			throw new SnapshotNotFoundException(String.format("Snapshot node with id=%s not found", uniqueNodeId));
		}
		
		Node parentNode = getParentNode(snapshotNode.getId());
		if(parentNode == null) {
			throw new IllegalArgumentException(String.format("Cannot commit snapshot id=%d as its parent is not found. Should not happen!", snapshotNode.getId()));
		}
		
		List<Node> parentsChildNodes = getChildNodes(parentNode.getUniqueId());
		parentsChildNodes.remove(snapshotNode);
		
		for(Node childNode : parentsChildNodes) {
			if(childNode.getNodeType().equals(NodeType.SNAPSHOT) && childNode.getName().equals(snapshotName)) {
				throw new IllegalArgumentException(String.format("A snapshot node with name=%s already exists for the configuration", snapshotName));
			}
		}
		
		jdbcTemplate.update("update node set name=?, username=?, last_modified=? where unique_id=?", snapshotName, userName, Timestamp.from(Instant.now()), uniqueNodeId);
		insertOrUpdateProperty(snapshotNode.getId(), new AbstractMap.SimpleEntry<String, String>("comment", comment));
	}
	
	@Transactional
	@Override
	public Node saveSnapshot(String parentsUniqueId, List<SnapshotItem> snapshotItems, String snapshotName, String comment, String userName) {
		Node snapshotNode = savePreliminarySnapshot(parentsUniqueId, snapshotItems);
		commitSnapshot(snapshotNode.getUniqueId(), snapshotName, userName, comment);
		
		return getSnapshot(snapshotNode.getUniqueId(), true);
	}

	/**
	 * Retrieves snapshot nodes that have been saved (committed). A saved snapshot records has a non-null user name value.
	 * @see NodeDAO#getSnapshots(java.lang.String)
	 */
	@Override
	public List<Node> getSnapshots(String uniqueNodeId) {
		
		List<Node> snapshots = jdbcTemplate.query("select n.*, nc.ancestor from node as n " +
				"join node_closure as nc on n.id=nc.descendant " +
				"where n.username is not NULL and nc.ancestor=(select id from node where unique_id=?) and nc.depth=1", new Object[] { uniqueNodeId }, 
				new NodeRowMapper());
		
		for(Node snapshot : snapshots) {
			snapshot.setProperties(getProperties(snapshot.getId()));
		}
		
		return snapshots;
		
	}
	
	@Override
	public List<SnapshotItem> getSnapshotItems(String snapshotUniqueId){
		
		List<SnapshotItem> snapshotItems = new ArrayList<>();
		
		List<SnapshotPv> snapshotPVs = jdbcTemplate.query("select * from snapshot_node_pv join config_pv on snapshot_node_pv.config_pv_id=config_pv.id "
				+ "where readback=false and snapshot_node_id=(select id from node where unique_id=?)",
				new Object[] {snapshotUniqueId}, 
				new SnapshotPvRowMapper());
		
		for(SnapshotPv snapshotPv : snapshotPVs) {
			// Should return zero or one element.
			List<SnapshotPv> readbacks = jdbcTemplate.query("select * from snapshot_node_pv join config_pv on snapshot_node_pv.config_pv_id=config_pv.id "
						+ "where readback=true and snapshot_node_id=(select id from node where unique_id=?)", 
						new Object[] {snapshotUniqueId}, 
						new SnapshotPvRowMapper());
			snapshotItems.add(SnapshotDataConverter.fromSnapshotPv(snapshotPv, readbacks.isEmpty() ? null : readbacks.get(0)));
		}
		
		return snapshotItems;
	}

	@Transactional
	@Override
	public Node getSnapshot(String uniqueNodeId, boolean committedOnly) {

		Node snapshotNode = getNode(uniqueNodeId);
		if(snapshotNode == null || !snapshotNode.getNodeType().equals(NodeType.SNAPSHOT) || (committedOnly && snapshotNode.getProperty("comment") == null)) {
			return null;
		}
		
		return snapshotNode;
	}
	
	@Transactional
	@Override
	public Node savePreliminarySnapshot(String parentsUniqueId, List<SnapshotItem> snapshotItems) {

		Node node = createNode(parentsUniqueId, Node.builder()
				.nodeType(NodeType.SNAPSHOT)
				.build());
			
		Map<String, Object> params = new HashMap<>(6);
		params.put("snapshot_node_id", node.getId());

		for (SnapshotItem snapshotItem : snapshotItems) {
			params.put("config_pv_id", snapshotItem.getConfigPv().getId());
			params.put("readback", 0);
			if (snapshotItem.getValue() != null) {
				SnapshotPv snapshotPv = SnapshotDataConverter.fromVType(snapshotItem.getValue());
				params.put("severity", snapshotPv.getAlarmSeverity().toString());
				params.put("status", snapshotPv.getAlarmStatus().toString());
				params.put("time", snapshotPv.getTime());
				params.put("timens", snapshotPv.getTimens());
				params.put("sizes", snapshotPv.getSizes());
				params.put("data_type", snapshotPv.getDataType().toString());
				params.put("value", snapshotPv.getValue());
			}

			snapshotPvInsert.execute(params);
			
			if (snapshotItem.getReadbackValue() != null) {
				SnapshotPv snapshotPv = SnapshotDataConverter.fromVType(snapshotItem.getReadbackValue());
				params.put("severity", snapshotPv.getAlarmSeverity().toString());
				params.put("status", snapshotPv.getAlarmStatus().toString());
				params.put("time", snapshotPv.getTime());
				params.put("timens", snapshotPv.getTimens());
				params.put("sizes", snapshotPv.getSizes());
				params.put("data_type", snapshotPv.getDataType().toString());
				params.put("value", snapshotPv.getValue());
				params.put("readback", 1);
				snapshotPvInsert.execute(params);
			}
		}

		return node;
	}
	
	/**
	 * Deletes all properties for the specified node id, and then inserts the properties
	 * as specified in the <code>properties</code> parameter. The client hence must make sure
	 * that any existing properties that should not be deleted are present in the map.
	 * 
	 * Keys and values of the map must all be non-null and non-empty in order to
	 * be inserted.
	 * 
	 * Specifying a <code>null<code> map of properties will delete all existing.
	 * @param nodeId The id of the {@link Node}
	 * @param properties Map of properties to insert.
	 */
	private void updateProperties(int nodeId, Map<String, String> properties) {
		
		jdbcTemplate.update("delete from property where node_id=?", nodeId);
		
		if(properties == null || properties.isEmpty()) {
			return;
		}
		
		for(Map.Entry<String, String> entry : properties.entrySet()) {
			insertOrUpdateProperty(nodeId, entry);
		}
	}
	
	/**
	 * This method is intentionally not using "on duplicate key" insert since that
	 * is tricky to set up for the H2 database used in unit testing.
	 * @param nodeId The node id identifying the owning node.
	 * @param entry The map entry (including key and value).
	 */
	private void insertOrUpdateProperty(int nodeId, Map.Entry<String, String> entry) {
		if(entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null || entry.getValue().isEmpty()) {
			return; // Ignore rather than throwing exception in order to not break callee's loop.
		}
		// Disallow setting the "root" property. It is set by Flyway.
		if("root".equals(entry.getKey())) {
			return;
		}
		// First check if there is an existing property for the combination of node id and key
		int numberOfHits = jdbcTemplate.queryForObject("select count(*) from property where node_id=? and property_name=?", 
				new Object[] {nodeId, entry.getKey()},
				Integer.class);
		if(numberOfHits == 0) {
			jdbcTemplate.update("insert into property values(?, ?, ?)", nodeId, entry.getKey(), entry.getValue());
		}
		else {
			jdbcTemplate.update("update property set value=? where node_id=? and property_name=?", entry.getValue(), nodeId, entry.getKey());
		}
	}
	
	private Map<String, String> getProperties(int nodeId){
		return jdbcTemplate.query("select * from property where node_id=?",
				new Object[] {nodeId}, new PropertiesRowMapper());
	}
	
	@Override
	public ConfigPv updateSingleConfigPv(String currentPVName, String newPVName, String currentReadbackPVName, String newReadbackPVName) {
		
		List<Integer> list;
		
		if(currentReadbackPVName == null || currentReadbackPVName.isEmpty()) {
			list = jdbcTemplate.queryForList("select id from config_pv where name=? and (readback_name is NULL or readback_name='')", 
					new Object[] { currentPVName }, Integer.class);
		}
		else {
			list = jdbcTemplate.queryForList("select id from config_pv where name=? and readback_name=?", 
					new Object[] { currentPVName, currentReadbackPVName}, Integer.class);
		}
		
		if(list.isEmpty()) {
			throw new IllegalArgumentException(String.format("Config pv with PV name=%s and read-back PV name=%s does not exist", currentPVName, currentReadbackPVName));
		}

		jdbcTemplate.update("update config_pv set name=?, readback_name=? where id=?", newPVName, newReadbackPVName, list.get(0));
		
		return jdbcTemplate.query("select * from config_pv where id=?", new Object[] {list.get(0)}, new ConfigPvRowMapper()).get(0);
	}
}