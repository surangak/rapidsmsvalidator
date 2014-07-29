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
package org.openmrs.module.rapidsmsvalidator.web.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifierType;
import org.openmrs.api.context.Context;
import org.openmrs.module.rapidsmsvalidator.api.RapidSMSValidatorService;
import org.openmrs.module.rapidsmsvalidator.api.util.RsmsNotificationHandler;
import org.openmrs.module.rheashradapter.model.PostEncounterLog;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.app.ApplicationException;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v25.message.ORU_R01;
import ca.uhn.hl7v2.model.v25.segment.PID;
import ca.uhn.hl7v2.parser.EncodingNotSupportedException;
import ca.uhn.hl7v2.parser.GenericParser;

/**
 * The main controller.
 */
@Controller
public class RapidSMSValidatorManageController {
	
	protected final Log log = LogFactory.getLog(getClass());
	
	@RequestMapping(value = "/module/rapidsmsvalidator/regenrateRapidSMSMessages", method = RequestMethod.GET)
	public void manage(@RequestParam(value = "fromId", required = false) int idStartRange,
	        @RequestParam(value = "toId", required = false) int idEndRange, HttpServletRequest request,
	        HttpServletResponse response) {
		System.out.println(" *** fromId " + idStartRange);
		System.out.println(" *** toId " + idEndRange);
		
		RapidSMSValidatorService service = Context.getService(RapidSMSValidatorService.class);
		GenericParser parser = new GenericParser();
		
		String hl7GivenName = null;
		String hl7FamilyName = null;
		ORU_R01 oru = null;
		PID pid = null;
		
		List<PostEncounterLog> postEncounterLogs = service.getPostEncounterLogs(idStartRange, idEndRange);
		
		System.out.println(" *** Begining analysis *** ");
		for (PostEncounterLog postEncounterLog : postEncounterLogs) {
			
			System.out.println(" *** Analyzing " + postEncounterLog.getPostRequestId() + " *** ");
			Message message = null;
			
			if (postEncounterLog.getHl7data() != null) {
				try {
					
					message = parser.parse(postEncounterLog.getHl7data());
					
					oru = (ORU_R01) message;
					
					if (oru.getMSH().getSendingApplication().getName().equals("RAPIDSMS")) {
						
						if (oru.getPATIENT_RESULT().getPATIENT().getVISIT().getPV1().getAdmissionType().getValue()
						        .toString().equals("RISK")) {
							System.out.println(" *** RISK message identified ! ");
							RsmsNotificationHandler rn = new RsmsNotificationHandler();
							try {
	                            rn.processMessage(oru);
                            }
                            catch (ApplicationException e) {
	                            // TODO Auto-generated catch block
	                            e.printStackTrace();
                            }
							
						}
						
						if (oru.getPATIENT_RESULT().getPATIENT().getVISIT().getPV1().getAdmissionType().getValue()
						        .toString().equals("MAT")) {
							System.out.println(" *** MAT message identified ! ");
							RsmsNotificationHandler rn = new RsmsNotificationHandler();
							try {
	                            rn.processMessage(oru);
                            }
                            catch (ApplicationException e) {
	                            // TODO Auto-generated catch block
	                            e.printStackTrace();
                            }	
							
						}
						
					} else {
						System.out.println(" *** Non-RapidSMS message *** ");
					}
					
				}
				catch (EncodingNotSupportedException e) {
					e.printStackTrace();
				}
				catch (HL7Exception e) {
					e.printStackTrace();
				}
				
			} else {
				System.out.println("*** Observation : Hl7 data not found ***");
			}
		}
		
		System.out.println(" *** Ending analysis *** ");
	}
	
	@RequestMapping(value = "/module/rapidsmsvalidator/editBirthEncounters", method = RequestMethod.GET)
	public void birth(HttpServletRequest request,
	        HttpServletResponse response) {
		System.out.println(" *** Initiating the editing of Birth encounters *** ");	
		List<Patient> patients = Context.getPatientService().getAllPatients();
		for (Patient p : patients) {
			System.out.println(" --------------------------------------------------------------------------------- ");
			System.out.println(" *** Testing Patient id " + p.getPatientId() + " for Birth encounters *** ");
			List<Encounter> encounters = Context.getEncounterService().getEncounters(p);
			for (Encounter e : encounters) {
				if (e.getEncounterType().getId() == 10) {
					System.out.println(" *** Birth encounter with Id " + e.getEncounterId() + " identified *** ");
					System.out.println(" *** Existing encounter DateTime is " + e.getEncounterDatetime() + " *** ");
					Set<Obs> obs = e.getAllObs();
					for (Obs o : obs) {
						if (o.getConcept().getConceptId() == 160259) {
							System.out.println(" *** New encounter DateTime is " + o.getValueDatetime() + " *** ");
							e.setEncounterDatetime(o.getValueDatetime());
						}
					}
					Context.getEncounterService().saveEncounter(e);
					System.out.println(" *** Encounters edited successfully *** ");

				}
			}
		}
		
		System.out.println(" *** Editing of Birth encounters is complete *** ");	

	}
	
	@RequestMapping(value = "/module/rapidsmsvalidator/voidRiskAndMatEncounters", method = RequestMethod.GET)
	public void delete(HttpServletRequest request,
	        HttpServletResponse response) {
		System.out.println(" *** Initiating the voiding of Risk and Mat encounters *** ");
		List<Patient> patients = Context.getPatientService().getAllPatients();
		for (Patient p : patients) {
			System.out.println(" *** Testing Patient id " + p.getPatientId() + " for Risk and Mat encounters *** ");
			List<Encounter> encounters = Context.getEncounterService().getEncounters(p);
			for (Encounter e : encounters) {
				if (e.getEncounterType().getId() == 9 || e.getEncounterType().getId() == 11) {
					System.out.println(" *** Identified encounter of type " + e.getEncounterType().getId() + " for voiding *** ");
					System.out.println(" *** Encounter id is " + e.getEncounterId() + " *** ");
					e.setVoided(true);
					e.setVoidReason("replaced");
					Context.getEncounterService().saveEncounter(e);
					System.out.println(" *** Encounter voided successfully *** ");
				}
			}
		}
		System.out.println(" *** Voiding Risk and Mat encounters is complete *** ");
	}	

}
