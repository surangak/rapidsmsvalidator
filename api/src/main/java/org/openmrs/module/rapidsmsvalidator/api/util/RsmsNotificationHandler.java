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

package org.openmrs.module.rapidsmsvalidator.api.util;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Concept;
import org.openmrs.ConceptAnswer;
import org.openmrs.ConceptName;
import org.openmrs.Drug;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Location;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifierType;
import org.openmrs.Person;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.PersonName;
import org.openmrs.api.context.Context;
import org.openmrs.hl7.HL7Constants;
import org.openmrs.hl7.handler.ProposingConceptException;
import org.openmrs.module.rheashradapter.api.LogEncounterService;
import org.openmrs.util.OpenmrsConstants;
import org.openmrs.util.OpenmrsUtil;
import org.springframework.util.StringUtils;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.app.Application;
import ca.uhn.hl7v2.app.ApplicationException;
import ca.uhn.hl7v2.model.DataTypeException;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Type;
import ca.uhn.hl7v2.model.Varies;
import ca.uhn.hl7v2.model.v25.datatype.CE;
import ca.uhn.hl7v2.model.v25.datatype.CWE;
import ca.uhn.hl7v2.model.v25.datatype.DT;
import ca.uhn.hl7v2.model.v25.datatype.DTM;
import ca.uhn.hl7v2.model.v25.datatype.FT;
import ca.uhn.hl7v2.model.v25.datatype.ID;
import ca.uhn.hl7v2.model.v25.datatype.NM;
import ca.uhn.hl7v2.model.v25.datatype.ST;
import ca.uhn.hl7v2.model.v25.datatype.TM;
import ca.uhn.hl7v2.model.v25.datatype.TS;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_OBSERVATION;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_PATIENT_RESULT;
import ca.uhn.hl7v2.model.v25.message.ORU_R01;
import ca.uhn.hl7v2.model.v25.segment.MSH;
import ca.uhn.hl7v2.model.v25.segment.OBR;
import ca.uhn.hl7v2.model.v25.segment.OBX;
import ca.uhn.hl7v2.model.v25.segment.PID;
import ca.uhn.hl7v2.model.v25.segment.PV1;

public class RsmsNotificationHandler implements Application {
	
	private String enterpriseId;
	private Log log = LogFactory.getLog(RsmsNotificationHandler.class);
	private LogEncounterService service = Context
			.getService(LogEncounterService.class);
	
	public RsmsNotificationHandler() {
	    super();
	    //this.enterpriseId = enterpriseId;
    }
	
	public Message processMessage(Message message) throws ApplicationException {
		
		if (!(message instanceof ORU_R01))
			throw new ApplicationException("Invalid message sent to ORU_R01 handler");
		
		log.debug("Processing ORU_R01 message");
		
		Message response;
		try {
			ORU_R01 oru = (ORU_R01) message;
			response = processORU_R01(oru);
		}
		catch (ClassCastException e) {
			log.error("Error casting " + message.getClass().getName() + " to ORU_R01", e);
			throw new ApplicationException("Invalid message type for handler");
		}
		catch (HL7Exception e) {
			log.error("Error while processing ORU_R01 message", e);
			throw new ApplicationException(e);
		}
		
		log.debug("Finished processing ORU_R01 message");
		
		return response;
	}
	
	@SuppressWarnings("deprecation")
	private Message processORU_R01(ORU_R01 oru) throws HL7Exception {

		MSH msh = getMSH(oru);
		PID pid = getPID(oru);
		PV1 pv1 = getPV1(oru);
		
		ORU_R01_PATIENT_RESULT result = oru.getPATIENT_RESULT();
		ORU_R01_ORDER_OBSERVATION order = result.getORDER_OBSERVATION(0);
			
			// the parent obr
			OBR obr = order.getOBR();
			
		
		Patient patient = getPatient(pid);
		Date encounterDatetime = getEncounterDate(obr);
		Encounter encounter = createEncounter(patient);
		EncounterType encType = getEncounterType(pv1); 
		Person provider = getProvider(pv1);
		Location location = getLocation(msh);
		encounter.setEncounterType(encType);
		encounter.setDateCreated(new Date());
		encounter.setProvider(provider);
		encounter.setLocation(location);
		encounter.setEncounterDatetime(encounterDatetime);
		
		Context.getEncounterService().saveEncounter(encounter);
		
		encounter = createObs(encounter, oru);
		
		obr.getFillerOrderNumber().getEntityIdentifier().setValue(encounter.getId().toString());
		
		return oru;
		
	}
	
	private Encounter createObs(Encounter encounter, ORU_R01 oru) throws HL7Exception {
		ORU_R01_PATIENT_RESULT patientResult = oru.getPATIENT_RESULT();
		MSH msh = getMSH(oru);
		
		ORU_R01_ORDER_OBSERVATION orderObs = null;
		orderObs = patientResult.getORDER_OBSERVATION(0);
		
		int numObs = orderObs.getOBSERVATIONReps();
		for (int j = 0; j < numObs; j++) {
			
			OBX obx = orderObs.getOBSERVATION(j).getOBX();
			String messageControlId = msh.getMessageControlID().getValue();
			Obs obs = parseObs(encounter, obx, messageControlId);
			encounter.addObs(obs);
		}
		
		return encounter;
	}

	private Obs parseObs(Encounter encounter, OBX obx, String uid) throws HL7Exception, ProposingConceptException {
		if (log.isDebugEnabled())
			log.debug("parsing observation: " + obx);
		Varies[] values = obx.getObservationValue();
		
		// bail out if no values were found
		if (values == null || values.length < 1)
			return null;
		
		String hl7Datatype = values[0].getName();
		if (log.isDebugEnabled())
			log.debug("  datatype = " + hl7Datatype);
		Concept concept = getConcept(obx.getObservationIdentifier(), uid);
		if (log.isDebugEnabled())
			log.debug("  concept = " + concept.getConceptId());
		ConceptName conceptName = getConceptName(obx.getObservationIdentifier());
		if (log.isDebugEnabled())
			log.debug("  concept-name = " + conceptName);
		
		Date datetime = getDatetime(obx);
		if (log.isDebugEnabled())
			log.debug("  timestamp = " + datetime);
		if (datetime == null)
			datetime = encounter.getEncounterDatetime();
		
		Obs obs = new Obs();
		obs.setUuid(UUID.randomUUID().toString());
		obs.setPerson(encounter.getPatient());
		obs.setConcept(concept);
		obs.setEncounter(encounter);
		obs.setObsDatetime(datetime);
		obs.setLocation(encounter.getLocation());
		obs.setCreator(encounter.getCreator());
		obs.setDateCreated(new Date());
		
		// set comments if there are any
		StringBuilder comments = new StringBuilder();
		ORU_R01_OBSERVATION parent = (ORU_R01_OBSERVATION) obx.getParent();
		// iterate over all OBX NTEs
		for (int i = 0; i < parent.getNTEReps(); i++)
			for (FT obxComment : parent.getNTE(i).getComment()) {
				if (comments.length() > 0)
					comments.append(" ");
				comments = comments.append(obxComment.getValue());
			}
		// only set comments if there are any
		if (StringUtils.hasText(comments.toString()))
			obs.setComment(comments.toString());
		
		Type obx5 = values[0].getData();
		if ("NM".equals(hl7Datatype)) {
			String value = ((NM) obx5).getValue();
			if (value == null || value.length() == 0) {
				log.warn("Not creating null valued obs for concept " + concept);
				return null;
			} else if (value.equals("0") || value.equals("1")) {
				concept = concept.hydrate(concept.getConceptId().toString());
				obs.setConcept(concept);
				if (concept.getDatatype().isBoolean())
					obs.setValueBoolean(value.equals("1"));
				else if (concept.getDatatype().isNumeric())
					try {
						obs.setValueNumeric(Double.valueOf(value));
					}
					catch (NumberFormatException e) {/*
						throw new HL7Exception("numeric (NM) value '" + value + "' is not numeric for concept #"
						        + concept.getConceptId() + " (" + conceptName.getName() + ") in message " + uid, e);
					*/}
				else if (concept.getDatatype().isCoded()) {
					Concept answer = value.equals("1") ? Context.getConceptService().getTrueConcept() : Context
					        .getConceptService().getFalseConcept();
					boolean isValidAnswer = false;
					Collection<ConceptAnswer> conceptAnswers = concept.getAnswers();
					if (conceptAnswers != null && conceptAnswers.size() > 0) {
						for (ConceptAnswer conceptAnswer : conceptAnswers) {
							if (conceptAnswer.getAnswerConcept().equals(answer)) {
								obs.setValueCoded(answer);
								isValidAnswer = true;
								break;
							}
						}
					}
					//answer the boolean answer concept was't found
					if (!isValidAnswer)
						throw new HL7Exception(answer.toString() + " is not a valid answer for obs with uuid ");
				} else {
					//throw this exception to make sure that the handler doesn't silently ignore bad hl7 message
					throw new HL7Exception("Can't set boolean concept answer for concept with id "
					        + obs.getConcept().getConceptId());
				}
			} else {
				try {
					obs.setValueNumeric(Double.valueOf(value));
				}
				catch (NumberFormatException e) {
					/*throw new HL7Exception("numeric (NM) value '" + value + "' is not numeric for concept #"
					        + concept.getConceptId() + " (" + conceptName.getName() + ") in message " + uid, e);*/
				}
			}
		} else if ("CWE".equals(hl7Datatype)) {
			log.debug("  CWE observation");
			CWE value = (CWE) obx5;
			String valueIdentifier = value.getIdentifier().getValue();
			log.debug("    value id = " + valueIdentifier);
			String valueName = value.getText().getValue();
			log.debug("    value name = " + valueName);
			if (isConceptProposal(valueIdentifier)) {
				if (log.isDebugEnabled())
					log.debug("Proposing concept");
				throw new ProposingConceptException(concept, valueName);
			} else {
				log.debug("    not proposal");
				try {
					Concept valueConcept = Context.getConceptService().getConceptByName(value.getIdentifier().getName().toString());
					obs.setValueCoded(valueConcept);
					if (HL7Constants.HL7_LOCAL_DRUG.equals(value.getNameOfAlternateCodingSystem().getValue())) {
						Drug valueDrug = new Drug();
						valueDrug.setDrugId(new Integer(value.getAlternateIdentifier().getValue()));
						obs.setValueDrug(valueDrug);
					} else {
						ConceptName valueConceptName = getConceptName(value);
						if (valueConceptName != null) {
							if (log.isDebugEnabled()) {
								log.debug("    value concept-name-id = " + valueConceptName.getConceptNameId());
								log.debug("    value concept-name = " + valueConceptName.getName());
							}
							obs.setValueCodedName(valueConceptName);
						}
					}
				}
				catch (NumberFormatException e) {
					throw new HL7Exception("Invalid concept ID '" + valueIdentifier + "' for OBX-5 value '" + valueName
					        + "'");
				}
			}
			if (log.isDebugEnabled())
				log.debug("  Done with CWE");
		} else if ("CE".equals(hl7Datatype)) {
			CE value = (CE) obx5;
			String valueIdentifier = value.getIdentifier().getValue();
			String valueName = value.getText().getValue();
			
			if (isConceptProposal(valueIdentifier)) {/*
				throw new ProposingConceptException(concept, valueName);
			*/} else {
				try {
					
					Concept c = getConcept(value, uid);
					obs.setValueCoded(c);
					ConceptName name = c.getName();
					obs.setValueCodedName(name);
				
				}
				catch (NumberFormatException e) {
					throw new HL7Exception("Invalid concept ID '" + valueIdentifier + "' for OBX-5 value '" + valueName
					        + "'");
				}
			}
		} else if ("DT".equals(hl7Datatype)) {
			DT value = (DT) obx5;
			Date valueDate = getDate(value.getYear(), value.getMonth(), value.getDay(), 0, 0, 0);
			if (value == null || valueDate == null) {
				log.warn("Not creating null valued obs for concept " + concept);
				return null;
			}
			obs.setValueDatetime(valueDate);
		} else if ("TS".equals(hl7Datatype)) {
			DTM value = ((TS) obx5).getTime();
			Date valueDate = getDate(value.getYear(), value.getMonth(), value.getDay(), value.getHour(), value.getMinute(),
			    value.getSecond());
			if (value == null || valueDate == null) {
				log.warn("Not creating null valued obs for concept " + concept);
				return null;
			}
			obs.setValueDatetime(valueDate);
		} else if ("TM".equals(hl7Datatype)) {
			TM value = (TM) obx5;
			Date valueTime = getDate(0, 0, 0, value.getHour(), value.getMinute(), value.getSecond());
			if (value == null || valueTime == null) {
				log.warn("Not creating null valued obs for concept " + concept);
				return null;
			}
			obs.setValueDatetime(valueTime);
		} else if ("ST".equals(hl7Datatype)) {
			ST value = (ST) obx5;
			if (value == null || value.getValue() == null || value.getValue().trim().length() == 0) {
				log.warn("Not creating null valued obs for concept " + concept);
				return null;
			}
			obs.setValueText(value.getValue());
		} else {
			// unsupported data type
			// TODO: support RP (report), SN (structured numeric)
			// do we need to support BIT just in case it slips thru?
			throw new HL7Exception("Unsupported observation datatype '" + hl7Datatype + "'");
		}
		
		return obs;
	}
	
	private Date getDatetime(OBX obx) throws HL7Exception {
		TS ts = obx.getDateTimeOfTheObservation();
		return getDatetime(ts);
	}
	
	private Date getDatetime(TS ts) throws HL7Exception {
		Date datetime = null;
		DTM value = ts.getTime();
		
		if (value.getYear() == 0 || value.getValue() == null)
			return null;
		
		try {
			datetime = getDate(value.getYear(), value.getMonth(), value.getDay(), value.getHour(), value.getMinute(), value
			        .getSecond());
		}
		catch (DataTypeException e) {

		}
		return datetime;
		
	}
	
	private Date getDate(int year, int month, int day, int hour, int minute, int second) {
		Calendar cal = Calendar.getInstance();
		// Calendar.set(MONTH, int) is zero-based, Hl7 is not
		cal.set(year, month - 1, day, hour, minute, second);
		return cal.getTime();
	}
	
	private Date getEncounterDate(OBR obr) throws HL7Exception {
		return tsToDate(obr.getObservationDateTime());
	}
	
	private Location getLocation(MSH msh) throws HL7Exception {
		String hl7Location = msh.getSendingFacility().getNamespaceID().toString();
		Location location = null;
		
		List<Location> locationsList = Context.getLocationService().getAllLocations();
		for(Location l : locationsList){
			String fosaid = l.getDescription();
			String elid = null;
			
			if(fosaid != null){
				final Matcher matcher = Pattern.compile(":").matcher(fosaid);
				if(matcher.find()){						
				    elid = fosaid.substring(matcher.end()).trim();
				    if(elid.equals(hl7Location)){
				    	location = l;
				    }
				}
			}
		}
		
		return location;
	}
	
	/**
	 * Derive a concept name from the CWE component of an hl7 message.
	 * 
	 * @param cwe
	 * @return
	 * @throws HL7Exception
	 */
	private ConceptName getConceptName(CWE cwe) throws HL7Exception {
		ST altIdentifier = cwe.getAlternateIdentifier();
		ID altCodingSystem = cwe.getNameOfAlternateCodingSystem();
		return getConceptName(altIdentifier, altCodingSystem);
	}
	
	/**
	 * Derive a concept name from the CE component of an hl7 message.
	 * 
	 * @param ce
	 * @return
	 * @throws HL7Exception
	 */
	private ConceptName getConceptName(CE ce) throws HL7Exception {
		ST altIdentifier = ce.getIdentifier();
		ID altCodingSystem = ce.getNameOfAlternateCodingSystem();
		return getConceptName(altIdentifier, altCodingSystem);
	}
	
	private ConceptName getConceptName(ST altIdentifier, ID altCodingSystem) throws HL7Exception {
		if (altIdentifier != null) {
			if (HL7Constants.HL7_LOCAL_CONCEPT_NAME.equals(altCodingSystem.getValue())) {
				String hl7ConceptNameId = altIdentifier.getValue();
				return getConceptName(hl7ConceptNameId);
			}else{
				String hl7ConceptNameId = altIdentifier.getValue();
				return getConceptName(hl7ConceptNameId);
			}
		}
		
		return null;
	}
	
	private ConceptName getConceptName(String hl7ConceptNameId) throws HL7Exception {
		ConceptName specifiedConceptName = null;
		if (hl7ConceptNameId != null) {
			// get the exact concept name specified by the id
			try {
				Integer conceptNameId = new Integer(hl7ConceptNameId);
				specifiedConceptName = new ConceptName();
				specifiedConceptName.setConceptNameId(conceptNameId);
			}
			catch (NumberFormatException e) {
				// if it is not a valid number, more than likely it is a bad hl7 message
				log.debug("Invalid concept name ID '" + hl7ConceptNameId + "'", e);
			}
		}
		return specifiedConceptName;
		
	}
	
	private Concept getConcept(CE codedElement, String uid) throws HL7Exception {
		String hl7ConceptId = codedElement.getIdentifier().getValue();
		
		String codingSystem = codedElement.getNameOfCodingSystem().getValue().toString();
		Concept concept = Context.getConceptService().getConceptByMapping(hl7ConceptId, codingSystem);

		return concept;
	}

	private boolean isConceptProposal(String identifier) {
		return OpenmrsUtil.nullSafeEquals(identifier, OpenmrsConstants.PROPOSED_CONCEPT_IDENTIFIER);
	}
	
	private Person getProvider(PV1 pv1) throws HL7Exception {
		String providerId = pv1.getAttendingDoctor(0).getIDNumber().getValue();
		
		List<PatientIdentifierType> identifierTypeList = null;
		Person provider = null;
		
		if (providerId != null) {
			
			Person person = service.getPersonByEPID(providerId);
		
			if(person != null)
			provider = person;
			else{
			
			log.info("ID extracted from the HL7 message does not match with SHR records, a new provider will be created...");
			
			Person candidate = new Person();
			candidate.addName(new PersonName(pv1.getAttendingDoctor(0).getGivenName().getValue(),"",pv1.getAttendingDoctor(0).getFamilyName().getSurname().getValue()));
			PersonAttributeType epidType = Context.getPersonService().getPersonAttributeTypeByName("EPID");
			
			if(epidType == null){
				log.info("Creating a PersonAttributeType for EPID since it does not exsist");
				epidType = new PersonAttributeType();
				epidType.setName("EPID");
				epidType.setDescription("Stores the EPID of the Provider");
				Context.getPersonService().savePersonAttributeType(epidType);
			}
			
			PersonAttributeType roleType = Context.getPersonService().getPersonAttributeTypeByName("Role");
			
			if(roleType == null){
				log.info("Creating a PersonAttributeType for Role since it does not exsist");
				roleType = new PersonAttributeType();
				roleType.setName("Role");
				roleType.setDescription("Stores the Role of the Person object");
				Context.getPersonService().savePersonAttributeType(roleType);
			}
			
			candidate.addAttribute(new PersonAttribute(epidType, providerId));
			candidate.addAttribute(new PersonAttribute(roleType, "Provider"));
			
			candidate.setGender("N/A");
			candidate = Context.getPersonService().savePerson(candidate);
			provider = candidate;
			}
		}
		return provider;
	}
	
	private EncounterType getEncounterType(PV1 pv1) throws HL7Exception {
		String encountertypeName = pv1.getAdmissionType().getValue().toString();
		if(encountertypeName.equals("BIR")){
			encountertypeName = "RapidSMS Notification BIRTH";
		}else if (encountertypeName.equals("RISK")){
			encountertypeName = "RapidSMS Notification RISK";
		}else if(encountertypeName.equals("MAT")){
			encountertypeName = "RapidSMS Notification Maternal Death";
		}
		EncounterType encType = Context.getEncounterService().getEncounterType(encountertypeName);
		if(encType == null){
			encType = new EncounterType(encountertypeName, encountertypeName + " Encounter type created by Rsms notification");
			Context.getEncounterService().saveEncounterType(encType);
		}
		return encType;
	}

	private Encounter createEncounter(Patient patient) {
		Encounter encounter = new Encounter();
		encounter.setPatient(patient);
		
	
		return encounter;
	}

	private Patient getPatient(PID pid) throws HL7Exception {
		Patient patient;
		PatientIdentifierType patientIdentifierType = Context.getPatientService().getPatientIdentifierTypeByName("ECID");
		List<PatientIdentifierType> identifierTypeList = new ArrayList<PatientIdentifierType>();
		identifierTypeList.add(patientIdentifierType);
		
		List<Patient> patients = Context.getPatientService().getPatients(null, enterpriseId, identifierTypeList, false);
		//I am not checking the identifier type here. Need to come back and add a check for this
		if(patients.size() == 1 ){
			patient = patients.get(0);
		}else{
			throw new HL7Exception("Could not resolve patient");
		}
		return patient;
	}

	private MSH getMSH(ORU_R01 oru) {
		return oru.getMSH();
	}
	
	private PV1 getPV1(ORU_R01 oru) {
		return oru.getPATIENT_RESULT().getPATIENT().getVISIT().getPV1();
	}
	
	public boolean canProcess(Message message) {
		return message != null && "ORU_R01".equals(message.getName());
	}
	
	private PID getPID(ORU_R01 oru) {
		return oru.getPATIENT_RESULT().getPATIENT().getPID();
	}
	
	private Date tsToDate(TS ts) throws HL7Exception {
		// need to handle timezone
		String dtm = ts.getTime().getValue();
		int year = Integer.parseInt(dtm.substring(0, 4));
		int month = (dtm.length() >= 6 ? Integer.parseInt(dtm.substring(4, 6)) - 1 : 0);
		int day = (dtm.length() >= 8 ? Integer.parseInt(dtm.substring(6, 8)) : 1);
		int hour = (dtm.length() >= 10 ? Integer.parseInt(dtm.substring(8, 10)) : 0);
		int min = (dtm.length() >= 12 ? Integer.parseInt(dtm.substring(10, 12)) : 0);
		int sec = (dtm.length() >= 14 ? Integer.parseInt(dtm.substring(12, 14)) : 0);
		Calendar cal = Calendar.getInstance();
		cal.set(year, month, day, hour, min, sec);

		return cal.getTime();
	}
	
}
