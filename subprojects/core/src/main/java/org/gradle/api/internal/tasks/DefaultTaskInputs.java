/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.tasks;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import groovy.lang.GString;
import org.gradle.api.Action;
import org.gradle.api.Describable;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.TaskInputsInternal;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.file.CompositeFileCollection;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.collections.FileCollectionResolveContext;
import org.gradle.api.tasks.TaskInputs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.gradle.api.internal.tasks.TaskPropertyUtils.ensurePropertiesHaveNames;
import static org.gradle.util.GUtil.uncheckedCall;

public class DefaultTaskInputs implements TaskInputsInternal {
    private final FileCollection allInputFiles;
    private final FileCollection allSourceFiles;
    private final FileResolver resolver;
    private final TaskInternal task;
    private final TaskMutator taskMutator;
    private final Map<String, Object> properties = new HashMap<String, Object>();
    private final List<TaskInputPropertySpecAndBuilder> filePropertiesInternal = Lists.newArrayList();
    private ImmutableSortedSet<TaskInputFilePropertySpec> fileProperties;
    private Action<TaskInternal> configureAction;

    public DefaultTaskInputs(FileResolver resolver, TaskInternal task, TaskMutator taskMutator) {
        this.resolver = resolver;
        this.task = task;
        this.taskMutator = taskMutator;
        String taskName = task.getName();
        this.allInputFiles = new TaskInputUnionFileCollection("input", false);
        this.allSourceFiles = new TaskInputUnionFileCollection("source", true);
    }

    @Override
    public boolean getHasInputs() {
        ensureConfigured();
        return !filePropertiesInternal.isEmpty() || !properties.isEmpty();
    }

    @Override
    public FileCollection getFiles() {
        return allInputFiles;
    }

    @Override
    public ImmutableSortedSet<TaskInputFilePropertySpec> getFileProperties() {
        ensureConfigured();
        if (fileProperties == null) {
            ensurePropertiesHaveNames(filePropertiesInternal);
            fileProperties = TaskPropertyUtils.<TaskInputFilePropertySpec>collectFileProperties("input", filePropertiesInternal.iterator());
        }
        return fileProperties;
    }

    @Override
    public TaskInputFilePropertyBuilderInternal files(final Object... paths) {
        ensureConfigured();
        return taskMutator.mutate("TaskInputs.files(Object...)", new Callable<TaskInputFilePropertyBuilderInternal>() {
            @Override
            public TaskInputFilePropertyBuilderInternal call() {
                return addSpec(paths);
            }
        });
    }

    @Override
    public TaskInputFilePropertyBuilderInternal file(final Object path) {
        ensureConfigured();
        return taskMutator.mutate("TaskInputs.file(Object)", new Callable<TaskInputFilePropertyBuilderInternal>() {
            @Override
            public TaskInputFilePropertyBuilderInternal call() {
                return addSpec(path);
            }
        });
    }

    @Override
    public TaskInputFilePropertyBuilderInternal dir(final Object dirPath) {
        ensureConfigured();
        return taskMutator.mutate("TaskInputs.dir(Object)", new Callable<TaskInputFilePropertyBuilderInternal>() {
            @Override
            public TaskInputFilePropertyBuilderInternal call() {
                return addSpec(resolver.resolveFilesAsTree(dirPath));
            }
        });
    }

    @Override
    public boolean getHasSourceFiles() {
        ensureConfigured();
        for (TaskInputPropertySpecAndBuilder propertySpec : filePropertiesInternal) {
            if (propertySpec.isSkipWhenEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public FileCollection getSourceFiles() {
        return allSourceFiles;
    }

    private TaskInputFilePropertyBuilderInternal addSpec(Object paths) {
        DefaultTaskInputPropertySpec spec = new DefaultTaskInputPropertySpec(task.getName(), resolver, paths);
        filePropertiesInternal.add(spec);
        return spec;
    }

    public Map<String, Object> getProperties() {
        ensureConfigured();
        Map<String, Object> actualProperties = new HashMap<String, Object>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String propertyName = entry.getKey();
            try {
                Object value = prepareValue(entry.getValue());
                actualProperties.put(propertyName, value);
            } catch (Exception ex) {
                throw new InvalidUserDataException(String.format("Error while evaluating property '%s' of %s", propertyName, task), ex);
            }
        }
        return actualProperties;
    }

    private Object prepareValue(Object value) {
        while (true) {
            if (value instanceof Callable) {
                Callable callable = (Callable) value;
                value = uncheckedCall(callable);
            } else if (value instanceof FileCollection) {
                FileCollection fileCollection = (FileCollection) value;
                return fileCollection.getFiles();
            } else {
                return avoidGString(value);
            }
        }
    }

    private static Object avoidGString(Object value) {
        return (value instanceof GString) ? value.toString() : value;
    }

    public TaskInputs property(final String name, final Object value) {
        ensureConfigured();
        taskMutator.mutate("TaskInputs.property(String, Object)", new Runnable() {
            public void run() {
                properties.put(name, value);
            }
        });
        return this;
    }

    public TaskInputs properties(final Map<String, ?> newProps) {
        ensureConfigured();
        taskMutator.mutate("TaskInputs.properties(Map)", new Runnable() {
            public void run() {
                properties.putAll(newProps);
            }
        });
        return this;
    }

    @Override
    public void whenPropertiesRequired(Action<TaskInternal> action) {
        this.configureAction = action;
    }

    private void ensureConfigured() {
        if (configureAction != null) {
            configureAction.execute(task);
        }
    }

    private class TaskInputUnionFileCollection extends CompositeFileCollection implements Describable {
        private final boolean skipWhenEmptyOnly;
        private final String type;

        public TaskInputUnionFileCollection(String type, boolean skipWhenEmptyOnly) {
            this.type = type;
            this.skipWhenEmptyOnly = skipWhenEmptyOnly;
        }

        @Override
        public String getDisplayName() {
            return "task '" + task.getName() + "' " + type + " files";
        }

        @Override
        public void visitContents(FileCollectionResolveContext context) {
            ensureConfigured();
            for (TaskInputPropertySpecAndBuilder fileProperty : filePropertiesInternal) {
                if (!skipWhenEmptyOnly || fileProperty.isSkipWhenEmpty()) {
                    context.add(fileProperty.getPropertyFiles());
                }
            }
        }
    }
}
