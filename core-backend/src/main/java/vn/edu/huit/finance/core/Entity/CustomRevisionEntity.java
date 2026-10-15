package vn.edu.huit.finance.core.Entity;

import jakarta.persistence.Entity;
import org.hibernate.envers.DefaultRevisionEntity;
import org.hibernate.envers.RevisionEntity;
import vn.edu.huit.finance.core.Security.AuditRevisionListener;
@Entity
@RevisionEntity(AuditRevisionListener.class)
public class CustomRevisionEntity extends DefaultRevisionEntity {
    private String username;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
}
