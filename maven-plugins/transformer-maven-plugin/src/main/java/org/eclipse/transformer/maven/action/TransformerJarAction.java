/********************************************************************************
 * Copyright (c) Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: (EPL-2.0 OR Apache-2.0)
 ********************************************************************************/

package org.eclipse.transformer.maven.action;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;

import org.eclipse.transformer.action.Action;
import org.eclipse.transformer.action.ActionType;
import org.eclipse.transformer.action.ByteData;
import org.eclipse.transformer.action.Changes;
import org.eclipse.transformer.action.CompositeAction;
import org.eclipse.transformer.action.impl.ByteDataImpl;
import org.eclipse.transformer.action.impl.ContainerActionImpl;

import aQute.bnd.osgi.EmbeddedResource;
import aQute.bnd.osgi.Jar;
import aQute.bnd.osgi.ManifestResource;
import aQute.bnd.osgi.Resource;

public class TransformerJarAction extends ContainerActionImpl {
	private final boolean	overwrite;

	public TransformerJarAction(CompositeAction rootAction, boolean overwrite) {
		super(rootAction);
		this.overwrite = overwrite;
	}

	@Override
	public String getName() {
		return "Transformer Maven Plugin Action";
	}

	@Override
	public ActionType getActionType() {
		return ActionType.JAR;
	}

	@Override
	protected TransformerJarChanges newChanges() {
		return new TransformerJarChanges();
	}

	@Override
	public TransformerJarChanges getActiveChanges() {
		return (TransformerJarChanges) super.getActiveChanges();
	}

	@Override
	public TransformerJarChanges getLastActiveChanges() {
		return (TransformerJarChanges) super.getLastActiveChanges();
	}

	public void apply(Jar jar, String inputName, String outputName) {
		String manifestName = jar.getManifestName();
		startRecording(inputName);
		try {
			setResourceNames(inputName, outputName);

			Map<String, Resource> resources = jar.getResources();
			List<String> inputPaths = new ArrayList<>(resources.size() + 1);
			if (!resources.containsKey(manifestName)) {
				inputPaths.add(manifestName); // process manifest first
			}
			inputPaths.addAll(resources.keySet());
			for (String inputPath : inputPaths) {
				Action selectedAction = acceptAction(inputPath);
				if (selectedAction == null) {
					recordUnaccepted(inputPath);
					continue;
				}
				if (!select(inputPath)) {
					recordUnselected(selectedAction, inputPath);
					continue;
				}
				try {
					Resource resource = jar.getResource(inputPath);
					if (inputPath.equals(manifestName)) {
						if (resource == null) {
							Manifest manifest = jar.getManifest();
							if (manifest == null) {
								continue; // no calculated manifest
							}
							resource = new ManifestResource(manifest);
						}
					}
					ByteBuffer bb = resource.buffer();
					ByteData inputData;
					if (bb != null) {
						inputData = new ByteDataImpl(inputPath, bb);
					} else {
						inputData = selectedAction.collect(inputPath, resource.openInputStream(),
							Math.toIntExact(resource.size()));
					}
					ByteData outputData = selectedAction.apply(inputData);
					recordTransform(selectedAction, inputPath);
					Changes changes = selectedAction.getLastActiveChanges();
					if (changes.hasChanges()) {
						String outputPath = outputData.name();
						getLogger().debug("[ {}.apply ]: Active transform [ {} ] [ {} ]", selectedAction.getClass()
							.getSimpleName(), inputPath, outputPath);
						if (changes.hasResourceNameChange()) {
							if (!isOverwrite() && (jar.getResource(outputPath) != null)) {
								getLogger().error(
									"Transform for {} overwrites existing resource {}. Use 'overwrite' option to allow overwriting.",
									inputPath, outputPath);
								continue;
							}
							jar.remove(inputPath);
							getActiveChanges().addRemoved(inputPath);
						}
						Resource outputResource = changes.hasNonResourceNameChanges()
							? new EmbeddedResource(outputData.buffer(), resource.lastModified())
							: resource;
						jar.putResource(outputPath, outputResource);
						getActiveChanges().addChanged(outputPath);
					}
				} catch (Exception e) {
					getLogger().error("Failure while transforming {}", inputPath, e);
				}
			}
		} finally {
			stopRecording(inputName);
		}
	}

	/**
	 * @return the overwrite
	 */
	public boolean isOverwrite() {
		return overwrite;
	}
}
