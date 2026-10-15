package vn.edu.huit.finance.core.Security;

import org.hibernate.envers.RevisionListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.edu.huit.finance.core.Entity.CustomRevisionEntity;

public class AuditRevisionListener implements RevisionListener {
    @Override
    public void newRevision(Object revisionEntity) {
        CustomRevisionEntity customRevEntity = (CustomRevisionEntity) revisionEntity;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !auth.getPrincipal().equals("anonymousUser")) {
            customRevEntity.setUsername(auth.getName());
        } else {
            customRevEntity.setUsername("System_AI");
        }
    }
}
