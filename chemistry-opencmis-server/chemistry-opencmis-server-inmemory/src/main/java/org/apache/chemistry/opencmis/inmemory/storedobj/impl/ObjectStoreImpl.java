/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.inmemory.storedobj.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Document;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.DocumentVersion;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Folder;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.MultiFiling;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.ObjectStore;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.SingleFiling;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoredObject;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.VersionedDocument;

/**
 * The object store is the central core of the in-memory repository. It is based on huge HashMap
 * map mapping ids to objects in memory. To allow access from multiple threads a Java concurrent
 * HashMap is used that allows parallel access methods.
 * <p>
 * Certain methods in the in-memory repository must guarantee constraints. For example a folder
 * enforces that each child has a unique name. Therefore certain operations must occur in an
 * atomic manner. In the example it must be guaranteed that no write access occurs to the
 * map between acquiring the iterator to find the children and finishing the add operation when
 * no name conflicts can occur. For this purpose this class has methods to lock an unlock the
 * state of the repository. It is very important that the caller acquiring the lock enforces an
 * unlock under all circumstances. Typical code is:
 * <p>
 * <pre>
 * ObjectStoreImpl os = ... ;
 * try {
 *     os.lock();
 * } finally {
 *     os.unlock();
 * }
 * </pre>
 *
 * The locking is very coarse-grained. Productive implementations would probably implement finer
 * grained locks on a folder or document rather than the complete repository.
 */
public class ObjectStoreImpl implements ObjectStore {

    /**
     * Simple id generator that uses just an integer
     */
    private static int NEXT_UNUSED_ID = 100;

    /**
     * a concurrent HashMap as core element to hold all objects in the repository
     */
    private final Map<String, StoredObject> fStoredObjectMap = new ConcurrentHashMap<String, StoredObject>();

    private final Lock fLock = new ReentrantLock();

    final String fRepositoryId;
    FolderImpl fRootFolder = null;

    public ObjectStoreImpl(String repositoryId) {
        fRepositoryId = repositoryId;
        createRootFolder();
    }

    private static synchronized Integer getNextId() {
        return NEXT_UNUSED_ID++;
    }

    public void lock() {
      fLock.lock();
    }

    public void unlock() {
      fLock.unlock();
    }

    public Folder getRootFolder() {
        return fRootFolder;
    }

    public StoredObject getObjectByPath(String path) {
        for (StoredObject so : fStoredObjectMap.values()) {
            if (so instanceof SingleFiling) {
                String soPath = ((SingleFiling) so).getPath();
                if (soPath.equals(path)) {
                    return so;
                }
            } else if (so instanceof MultiFiling) {
                MultiFiling mfo = (MultiFiling) so;
                List<Folder> parents = mfo.getParents();
                for (Folder parent : parents) {
                    String parentPath = parent.getPath();
                    String mfPath = parentPath.equals(Folder.PATH_SEPARATOR) ? parentPath + mfo.getPathSegment()
                            : parentPath + Folder.PATH_SEPARATOR + mfo.getPathSegment();
                    if (mfPath.equals(path)) {
                        return so;
                    }
                }
            } else {
                return null;
            }
        }
        return null;
    }

    public StoredObject getObjectById(String objectId) {
        // we use path as id so we just can look it up in the map
        StoredObject so = fStoredObjectMap.get(objectId);
        return so;
    }

    public void deleteObject(String objectId) {
        String path = objectId; // currently the same
        StoredObject obj = fStoredObjectMap.get(path);

        if (null == obj) {
            throw new RuntimeException("Cannot delete object with id  " + objectId + ". Object does not exist.");
        }

        if (obj instanceof FolderImpl) {
            deleteFolder(objectId);
        } else if (obj instanceof DocumentVersion) {
            DocumentVersion vers = (DocumentVersion) obj;
            VersionedDocument parentDoc = vers.getParentDocument();
            fStoredObjectMap.remove(path);
            boolean otherVersionsExist = vers.getParentDocument().deleteVersion(vers);
            if (!otherVersionsExist) {
                fStoredObjectMap.remove(parentDoc.getId());
            }
        } else {
            fStoredObjectMap.remove(path);
        }
    }

    public void removeVersion(DocumentVersion vers) {
        StoredObject found = fStoredObjectMap.remove(vers.getId());

        if (null == found) {
            throw new CmisInvalidArgumentException("Cannot delete object with id  " + vers.getId() + ". Object does not exist.");
        }
    }

    // public void changePath(StoredObject obj, String oldPath, String newPath)
    // {
    // fStoredObjectMap.remove(oldPath);
    // fStoredObjectMap.put(newPath, obj);
    // }

    // /////////////////////////////////////////
    // methods used by folders and documents, but not for public use

    // void storeObject(String id, StoredObject sop) {
    // fStoredObjectMap.put(id, sop);
    // }

    public String storeObject(StoredObject so) {
        String id = so.getId();
        // check if update or create
        if (null == id) {
            id = getNextId().toString();
        }
        fStoredObjectMap.put(id, so);
        return id;
    }

    StoredObject getObject(String id) {
        return fStoredObjectMap.get(id);
    }

    void removeObject(String id) {
        fStoredObjectMap.remove(id);
    }

    public Set<String> getIds() {
        Set<String> entries = fStoredObjectMap.keySet();
        return entries;
    }

    /**
     * Clear repository and remove all data.
     */
    public void clear() {
        lock();
        fStoredObjectMap.clear();
        storeObject(fRootFolder);
        unlock();
    }

    public long getObjectCount() {
        return fStoredObjectMap.size();
    }

    // /////////////////////////////////////////
    // private helper methods

    private void createRootFolder() {
        FolderImpl rootFolder = new FolderImpl(this);
        rootFolder.setName("RootFolder");
        rootFolder.setParent(null);
        rootFolder.setTypeId(BaseTypeId.CMIS_FOLDER.value());
        rootFolder.setCreatedBy("Admin");
        rootFolder.setModifiedBy("Admin");
        rootFolder.setModifiedAtNow();
        rootFolder.setRepositoryId(fRepositoryId);
        rootFolder.persist();
        fRootFolder = rootFolder;
    }

    public Document createDocument(String name) {
        Document doc = new DocumentImpl(this);
        doc.setRepositoryId(fRepositoryId);
        doc.setName(name);
        return doc;
    }

    public VersionedDocument createVersionedDocument(String name) {
        VersionedDocument doc = new VersionedDocumentImpl(this);
        doc.setRepositoryId(fRepositoryId);
        doc.setName(name);
        return doc;
    }

    public Folder createFolder(String name) {
        Folder folder = new FolderImpl(this, name, null);
        folder.setRepositoryId(fRepositoryId);
        return folder;
    }

    public List<StoredObject> getCheckedOutDocuments(String orderBy) {
        List<StoredObject> res = new ArrayList<StoredObject>();

        for (StoredObject so : fStoredObjectMap.values()) {
            if (so instanceof VersionedDocument) {
                VersionedDocument verDoc = (VersionedDocument) so;
                if (verDoc.isCheckedOut()) {
                    res.add(verDoc);
                }
            }
        }

        return res;
    }

    private void deleteFolder(String folderId) {
        StoredObject folder = fStoredObjectMap.get(folderId);
        if (folder == null) {
            throw new CmisInvalidArgumentException("Unknown object with id:  " + folderId);
        }

        if (!(folder instanceof FolderImpl)) {
            throw new CmisInvalidArgumentException("Cannot delete folder with id:  " + folderId
                    + ". Object exists but is not a folder.");
        }

        // check if children exist
        List<StoredObject> children = ((Folder) folder).getChildren(-1, -1);
        if (children != null && !children.isEmpty()) {
            throw new CmisConstraintException("Cannot delete folder with id:  " + folderId + ". Folder is not empty.");
        }

        fStoredObjectMap.remove(folderId);
    }

}
