package ch.yuno.policy.infrastructure.persistence.audit;

import org.hibernate.envers.RevisionListener;

public class CustomRevisionListener implements RevisionListener {

    @Override
    public void newRevision(Object revisionEntity) {
        CustomRevisionEntity rev = (CustomRevisionEntity) revisionEntity;
        // In a real application this would read from the security context
        rev.setChangedBy("system");
    }
}

