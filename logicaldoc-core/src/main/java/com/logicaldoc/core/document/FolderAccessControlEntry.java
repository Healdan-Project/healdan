package com.logicaldoc.core.document;

import java.util.Set;

import com.logicaldoc.core.security.ExtendedAccessControlEntry;
import com.logicaldoc.core.security.Permission;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;

/**
 * Represents all the permissions granted to a group against a business object
 * 
 * @author Marco Meschieri - LogicalDOC
 * @since 8.9.1
 */
@Embeddable
public class FolderAccessControlEntry extends ExtendedAccessControlEntry {

    private static final long serialVersionUID = 1L;

    @Embedded
    private ExtendedAccessControlEntry ace = new ExtendedAccessControlEntry();

    @Column(name = "ld_add", nullable = false)
    private boolean add = false;

    @Column(name = "ld_import", nullable = false)
    private boolean iimport = false;

    @Column(name = "ld_export", nullable = false)
    private boolean export = false;

    @Column(name = "ld_store", nullable = false)
    private boolean store = false;

    public FolderAccessControlEntry() {
    }

    public FolderAccessControlEntry(FolderAccessControlEntry source) {
        setGroupId(source.getGroupId());
        setRead(source.isRead());
        setWrite(source.isWrite());

        setArchive(source.isArchive());
        setAutomation(source.isAutomation());
        setCalendar(source.isCalendar());
        setDelete(source.isDelete());
        setDownload(source.isDownload());
        setEmail(source.isEmail());
        setImmutable(source.isImmutable());
        setMove(source.isMove());
        setMove(source.isMove());
        setPassword(source.isPassword());
        setPreview(source.isPreview());
        setPrint(source.isPrint());
        setPrint(source.isPrint());
        setRename(source.isRename());
        setSecurity(source.isSecurity());
        setSign(source.isSign());
        setSubscription(source.isSubscription());
        setWorkflow(source.isWorkflow());
        setReadingreq(source.isReadingreq());
        setCustomid(source.isCustomid());
        setRevision(source.isRevision());

        setAdd(source.isAdd());
        setImport(source.isImport());
        setExport(source.isExport());
        setStore(source.isStore());
    }

    public FolderAccessControlEntry(long groupId) {
        super(groupId);
        setGroupId(groupId);
    }

    @Override
    public ExtendedAccessControlEntry getAce() {
        return ace;
    }

    public void setAce(ExtendedAccessControlEntry ace) {
        this.ace = ace;
    }

    @Override
    public Set<Permission> grantedPermissions() {
        Set<Permission> granted = ace.grantedPermissions();
        if (add)
            granted.add(Permission.ADD);
        if (export)
            granted.add(Permission.EXPORT);
        if (iimport)
            granted.add(Permission.IMPORT);
        if (store)
            granted.add(Permission.STORE);
        return granted;
    }

    @Override
    public void grantPermissions(Set<Permission> permissions) {
        super.grantPermissions(permissions);
        ace.grantPermissions(permissions);
        add = permissions.contains(Permission.ADD);
        export = permissions.contains(Permission.EXPORT);
        iimport = permissions.contains(Permission.IMPORT);
        store = permissions.contains(Permission.STORE);
    }

    @Override
    public void setGroupId(long groupId) {
        ace.setGroupId(groupId);
    }

    @Override
    public long getGroupId() {
        return ace.getGroupId();
    }

    @Override
    public boolean isRead() {
        return ace.isRead();
    }

    @Override
    public void setRead(boolean read) {
        ace.setRead(read);
    }

    @Override
    public boolean isWrite() {
        return ace.isWrite();
    }

    @Override
    public void setWrite(boolean write) {
        ace.setWrite(write);
    }

    @Override
    public boolean isPreview() {
        return ace.isPreview();
    }

    @Override
    public void setPreview(boolean preview) {
        ace.setPreview(preview);
    }

    @Override
    public boolean isDownload() {
        return ace.isDownload();
    }

    @Override
    public void setDownload(boolean download) {
        ace.setDownload(download);
    }

    @Override
    public boolean isSecurity() {
        return ace.isSecurity();
    }

    @Override
    public void setSecurity(boolean security) {
        ace.setSecurity(security);
    }

    @Override
    public boolean isDelete() {
        return ace.isDelete();
    }

    @Override
    public void setDelete(boolean delete) {
        ace.setDelete(delete);
    }

    @Override
    public boolean isRename() {
        return ace.isRename();
    }

    @Override
    public void setRename(boolean rename) {
        ace.setRename(rename);
    }

    @Override
    public boolean isImmutable() {
        return ace.isImmutable();
    }

    @Override
    public void setImmutable(boolean immutable) {
        ace.setImmutable(immutable);
    }

    @Override
    public boolean isSign() {
        return ace.isSign();
    }

    @Override
    public void setSign(boolean sign) {
        ace.setSign(sign);
    }

    @Override
    public boolean isArchive() {
        return ace.isArchive();
    }

    @Override
    public void setArchive(boolean archive) {
        ace.setArchive(archive);
    }

    @Override
    public boolean isWorkflow() {
        return ace.isWorkflow();
    }

    @Override
    public void setWorkflow(boolean workflow) {
        ace.setWorkflow(workflow);
    }

    @Override
    public boolean isCalendar() {
        return ace.isCalendar();
    }

    @Override
    public void setCalendar(boolean calendar) {
        ace.setCalendar(calendar);
    }

    @Override
    public boolean isSubscription() {
        return ace.isSubscription();
    }

    @Override
    public void setSubscription(boolean subscription) {
        ace.setSubscription(subscription);
    }

    @Override
    public boolean isPassword() {
        return ace.isPassword();
    }

    @Override
    public void setPassword(boolean password) {
        ace.setPassword(password);
    }

    @Override
    public boolean isPrint() {
        return ace.isPrint();
    }

    @Override
    public void setPrint(boolean print) {
        ace.setPrint(print);
    }

    @Override
    public boolean isMove() {
        return ace.isMove();
    }

    @Override
    public void setMove(boolean move) {
        ace.setMove(move);
    }

    @Override
    public boolean isEmail() {
        return ace.isEmail();
    }

    @Override
    public void setEmail(boolean email) {
        ace.setEmail(email);
    }

    @Override
    public boolean isAutomation() {
        return ace.isAutomation();
    }

    @Override
    public void setAutomation(boolean automation) {
        ace.setAutomation(automation);
    }

    @Override
    public boolean isReadingreq() {
        return ace.isReadingreq();
    }

    @Override
    public void setReadingreq(boolean readingreq) {
        ace.setReadingreq(readingreq);
    }

    @Override
    public boolean isCustomid() {
        return ace.isCustomid();
    }

    @Override
    public void setCustomid(boolean customid) {
        ace.setCustomid(customid);
    }

    @Override
    public boolean isRevision() {
        return ace.isRevision();
    }

    @Override
    public void setRevision(boolean revision) {
        ace.setRevision(revision);
    }

    public boolean isAdd() {
        return add;
    }

    public void setAdd(boolean add) {
        this.add = add;
    }

    public boolean isImport() {
        return iimport;
    }

    public void setImport(boolean imprt) {
        this.iimport = imprt;
    }

    public boolean isIimport() {
        return isIimport();
    }

    public void setIimport(boolean iimport) {
        setImport(iimport);
    }

    public boolean isExport() {
        return export;
    }

    public void setExport(boolean export) {
        this.export = export;
    }

    public boolean isStore() {
        return store;
    }

    public void setStore(boolean store) {
        this.store = store;
    }

    @Override
    public String toString() {
        return "FolderAccessControlEntry [ace=%s, add=%b, iimport=%b, export=%b, store=%b]".formatted(ace, add, iimport,
                export, store);
    }

    @Override
    public int hashCode() {
        // Healdan: by group, as equals()
        return Long.hashCode(getGroupId());
    }

    @Override
    public boolean equals(Object obj) {
        // Healdan: equal by group, as ld_folder_acl's key and
        // DocumentAccessControlEntry are. CE also compared the permissions and
        // the unmapped fields the group id constructor writes, so
        // Folder.addAccessControlEntry() added a second entry for a group
        return obj instanceof FolderAccessControlEntry other && getGroupId() == other.getGroupId();
    }
}