/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.rapidsmsvalidator.api.db.hibernate;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.Criteria;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Expression;
import org.openmrs.module.rapidsmsvalidator.api.db.RapidSMSValidatorDAO;
import org.openmrs.module.rheashradapter.model.PostEncounterLog;


/**
 * It is a default implementation of  {@link RapidSMSValidatorDAO}.
 */
public class HibernateRapidSMSValidatorDAO implements RapidSMSValidatorDAO {
	protected final Log log = LogFactory.getLog(this.getClass());
	
	private SessionFactory sessionFactory;
	
	/**
     * @param sessionFactory the sessionFactory to set
     */
    public void setSessionFactory(SessionFactory sessionFactory) {
	    this.sessionFactory = sessionFactory;
    }
    
	/**
     * @return the sessionFactory
     */
    public SessionFactory getSessionFactory() {
	    return sessionFactory;
    }
    
    @Override
    public List<PostEncounterLog> getPostEncounterLogs(int fromId, int toId) {
		
    Criteria crit = sessionFactory.getCurrentSession().createCriteria(org.openmrs.module.rheashradapter.model.PostEncounterLog.class);

		crit.add(Expression.ge("postRequestId", fromId));
		crit.add(Expression.le("postRequestId", toId));	    
	
	return crit.list();
	}
    
}