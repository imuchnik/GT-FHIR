package edu.gatech.i3l.fhir.jpa.dao;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContextType;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Subquery;

import org.springframework.beans.factory.annotation.Required;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.jpa.dao.BaseFhirDao;
import ca.uhn.fhir.jpa.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.dao.SearchParameterMap;
import ca.uhn.fhir.jpa.entity.BaseHasResource;
import ca.uhn.fhir.jpa.entity.BaseResourceIndexedSearchParam;
import ca.uhn.fhir.jpa.entity.ResourceTable;
import ca.uhn.fhir.jpa.util.StopWatch;
import ca.uhn.fhir.model.api.IQueryParameterType;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.api.ResourceMetadataKeyEnum;
import ca.uhn.fhir.model.dstu2.resource.Patient;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.model.primitive.InstantDt;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import edu.gatech.i3l.jpa.model.omop.BaseResourceTable;
import edu.gatech.i3l.jpa.model.omop.IResourceTable;
import edu.gatech.i3l.jpa.model.omop.Person;
import edu.gatech.i3l.jpa.model.omop.ext.PatientFhirExtTable;

/**
 * This class serves as Template with commmon dao functions that are meant to be extended by subclasses.
 * @author Ismael Sarmento
 */
@Transactional(propagation = Propagation.REQUIRED)
public abstract class BaseFhirResourceDao<T extends IResource> extends BaseFhirDao implements IFhirResourceDao<T>{
	
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(BaseFhirResourceDao.class);
	
	@PersistenceContext(type = PersistenceContextType.TRANSACTION)
	private EntityManager myEntityManager;
	
	private Class<T> myResourceType;

	@Override
	public Class<T> getResourceType() {
		return myResourceType;
	}
	
	@SuppressWarnings("unchecked")
	@Required
	public void setResourceType(Class<? extends IResource> theTableType) {
		myResourceType = (Class<T>) theTableType;
	}
	
	@Override
	public T read(IdDt theId) {
//		validateResourceTypeAndThrowIllegalArgumentException(theId);

		StopWatch w = new StopWatch();
		BaseResourceTable entity = (BaseResourceTable) readEntity(theId);
//		validateResourceType(entity);

		T retVal = entity.getRelatedResource();//toResource(myResourceType, entity);

		InstantDt deleted = ResourceMetadataKeyEnum.DELETED_AT.get(retVal);
		if (deleted != null && !deleted.isEmpty()) {
			throw new ResourceGoneException("Resource was deleted at " + deleted.getValueAsString());
		}

		ourLog.info("Processed read on {} in {}ms", theId.getValue(), w.getMillisAndRestart());
		return retVal;
	}
	
	//	private void validateResourceType(BaseHasResource entity) {
	//		if (!myResourceName.equals(entity.getResourceType())) {
	//			throw new ResourceNotFoundException("Resource with ID " + entity.getIdDt().getIdPart() + " exists but it is not of type " + myResourceName + ", found resource of type "
	//					+ entity.getResourceType());
	//		}
	//	}
	//	
	//	private void validateResourceTypeAndThrowIllegalArgumentException(IdDt theId) {
	//		if (theId.hasResourceType() && !theId.getResourceType().equals(myResourceName)) {
	//			throw new IllegalArgumentException("Incorrect resource type (" + theId.getResourceType() + ") for this DAO, wanted: " + myResourceName);
	//		}
	//	}


	@Override
	public BaseHasResource readEntity(IdDt theId) {
		boolean checkForForcedId = true;
	
		BaseHasResource entity = readEntity(theId, checkForForcedId);
	
		return entity;
	}
	
	@Override
	public BaseHasResource readEntity(IdDt theId, boolean theCheckForForcedId) {
		//validateResourceTypeAndThrowIllegalArgumentException(theId);
	
		Long pid = theId.getIdPartAsLong();//translateForcedIdToPid(theId); //WARNING ForcedId strategy 
		BaseHasResource entity = myEntityManager.find(Person.class, pid);
	//	if (theId.hasVersionIdPart()) { //FIXME implement the versioning check
	//		if (entity.getVersion() != theId.getVersionIdPartAsLong()) {
	//			entity = null;
	//		}
	//	}
	//
	//	if (entity == null) {
	//		if (theId.hasVersionIdPart()) {
	//			TypedQuery<ResourceHistoryTable> q = myEntityManager.createQuery(
	//					"SELECT t from ResourceHistoryTable t WHERE t.myResourceId = :RID AND t.myResourceType = :RTYP AND t.myResourceVersion = :RVER", ResourceHistoryTable.class);
	//			q.setParameter("RID", pid);
	//			q.setParameter("RTYP", myResourceType);//WARNING originally myResourceName
	//			q.setParameter("RVER", theId.getVersionIdPartAsLong());
	//			entity = q.getSingleResult();
	//		}
	//		if (entity == null) {
	//			throw new ResourceNotFoundException(theId);
	//		}
	//	}
	
		//validateResourceType(entity);
	
		if (theCheckForForcedId) {
			//validateGivenIdIsAppropriateToRetrieveResource(theId, entity);//WARNING checks for forcedId
		}
		return entity;
	}
	
	
	/*
	 * ********************
	 * COMMON METHODS
	 * ********************
	 */
	@Override
	public Set<Long> searchForIdsWithAndOr(SearchParameterMap theParams) {
		SearchParameterMap params = theParams;
		if (params == null) {
			params = new SearchParameterMap();
		}

		RuntimeResourceDefinition resourceDef = getContext().getResourceDefinition(myResourceType);

		Set<Long> pids = new HashSet<Long>();

		for (Entry<String, List<List<? extends IQueryParameterType>>> nextParamEntry : params.entrySet()) {
			String nextParamName = nextParamEntry.getKey();
			if (nextParamName.equals("_id")) {

				if (nextParamEntry.getValue().isEmpty()) {
					continue;
				} else if (nextParamEntry.getValue().size() > 1) {
					throw new InvalidRequestException("AND queries not supported for _id (Multiple instances of this param found)");
				} else {
					Set<Long> joinPids = new HashSet<Long>();
					List<? extends IQueryParameterType> nextValue = nextParamEntry.getValue().get(0);
					if (nextValue == null || nextValue.size() == 0) {
						continue;
					} else {
						for (IQueryParameterType next : nextValue) {
							String value = next.getValueAsQueryToken();
							IdDt valueId = new IdDt(value);
							try {
								if (isValidPid(valueId)) {
									long valueLong =  valueId.getIdPartAsLong();
									//translateForcedIdToPid(valueId); //WARNING altered line
									joinPids.add(valueLong);
								}
							} catch (ResourceNotFoundException e) {
								// This isn't an error, just means no result found
							}
						}
						if (joinPids.isEmpty()) {
							continue;
						}
					}

					pids = addPredicateId(pids, joinPids);
					if (pids.isEmpty()) {
						return new HashSet<Long>();
					}

					if (pids.isEmpty()) {
						pids.addAll(joinPids);
					} else {
						pids.retainAll(joinPids);
					}
				}

			} 
			else if (nextParamName.equals("_language")) {

				//pids = addPredicateLanguage(pids, nextParamEntry.getValue());

			} else {

				RuntimeSearchParam nextParamDef = resourceDef.getSearchParam(nextParamName);
				if (nextParamDef != null) {
					switch (nextParamDef.getParamType()) {
					case DATE:
						for (List<? extends IQueryParameterType> nextAnd : nextParamEntry.getValue()) {
							pids = addPredicateDate(nextParamName, pids, nextAnd);
							if (pids.isEmpty()) {
								return new HashSet<Long>();
							}
						}
						break;
					case QUANTITY:
						for (List<? extends IQueryParameterType> nextAnd : nextParamEntry.getValue()) {
							//pids = addPredicateQuantity(nextParamName, pids, nextAnd);
							if (pids.isEmpty()) {
								return new HashSet<Long>();
							}
						}
						break;
					case REFERENCE:
						for (List<? extends IQueryParameterType> nextAnd : nextParamEntry.getValue()) {
							//pids = addPredicateReference(nextParamName, pids, nextAnd);
							if (pids.isEmpty()) {
								return new HashSet<Long>();
							}
						}
						break;
					case STRING:
						for (List<? extends IQueryParameterType> nextAnd : nextParamEntry.getValue()) {
							//pids = addPredicateString(nextParamName, pids, nextAnd);
							if (pids.isEmpty()) {
								return new HashSet<Long>();
							}
						}
						break;
					case TOKEN:
						for (List<? extends IQueryParameterType> nextAnd : nextParamEntry.getValue()) {
							//pids = addPredicateToken(nextParamName, pids, nextAnd);
							if (pids.isEmpty()) {
								return new HashSet<Long>();
							}
						}
						break;
					case NUMBER:
						for (List<? extends IQueryParameterType> nextAnd : nextParamEntry.getValue()) {
							//pids = addPredicateNumber(nextParamName, pids, nextAnd);
							if (pids.isEmpty()) {
								return new HashSet<Long>();
							}
						}
						break;
					case COMPOSITE:
						for (List<? extends IQueryParameterType> nextAnd : nextParamEntry.getValue()) {
							//pids = addPredicateComposite(nextParamDef, pids, nextAnd);
							if (pids.isEmpty()) {
								return new HashSet<Long>();
							}
						}
						break;
					default:
						break;
					}
				}
			}
		}

		return pids;
	}
	
	
	/*
	 * **********************
	 * PREDICATES
	 * **********************
	 */
	protected Set<Long> addPredicateId(Set<Long> theExistingPids, Set<Long> thePids) {
		if (thePids == null || thePids.isEmpty()) {
			return Collections.emptySet();
		}

		CriteriaBuilder builder = myEntityManager.getCriteriaBuilder();
		CriteriaQuery<Long> cq = builder.createQuery(Long.class);
		Root<Person> from = cq.from(Person.class);//Removed ResourceTable
		cq.select(from.get("id").as(Long.class)); 

//		Predicate typePredicate = builder.equal(from.get("myResourceType"), myResourceName);
		Predicate idPrecidate = from.get("id").in(thePids);

//		cq.where(builder.and(typePredicate, idPrecidate));
		cq.where(idPrecidate);
		TypedQuery<Long> q = myEntityManager.createQuery(cq);
		HashSet<Long> found = new HashSet<Long>(q.getResultList());
		if (!theExistingPids.isEmpty()) {
			theExistingPids.retainAll(found);
		}

		return found;
	}
	
	protected Set<Long> addPredicateDate(String theParamName, Set<Long> thePids, List<? extends IQueryParameterType> theList) {
		if (theList == null || theList.isEmpty()) {
			return thePids;
		}

//		if (Boolean.TRUE.equals(theList.get(0).getMissing())) {
//			return addPredicateParamMissing(thePids, "myParamsDate", theParamName, ResourceIndexedSearchParamDate.class);
//		}

		CriteriaBuilder builder = myEntityManager.getCriteriaBuilder();
		CriteriaQuery<Long> cq = builder.createQuery(Long.class);
		Root<Person> from = cq.from(Person.class);
		cq.select(from.get("id").as(Long.class));
		
//		Root<ResourceIndexedSearchParamDate> from = cq.from(ResourceIndexedSearchParamDate.class);
//		cq.select(from.get("myResourcePid").as(Long.class));
//
		List<Predicate> codePredicates = new ArrayList<Predicate>();
		for (IQueryParameterType nextOr : theList) {
			
			if (addPredicateMissingFalseIfPresent(builder, theParamName, from, codePredicates, nextOr)) {
				continue;
			}

			IQueryParameterType params = nextOr;
			Predicate p = createPredicateDate(builder, from, theParamName, params);
			codePredicates.add(p);
		}

		Predicate masterCodePredicate = builder.or(codePredicates.toArray(new Predicate[0]));

//		Predicate type = builder.equal(from.get("myResourceType"), myResourceType);
//		Predicate name = builder.equal(from.get("myParamName"), theParamName);
		if (thePids.size() > 0) {
			Predicate inPids = (from.get("id").in(thePids));//WARNING included previously 'name' 'type' and 'masterCodePredicate'
			cq.where(builder.and(inPids, masterCodePredicate));
		} else {
			cq.where(builder.and(masterCodePredicate));
		}

		TypedQuery<Long> q = myEntityManager.createQuery(cq);
		return new HashSet<Long>(q.getResultList());
	}
	
	protected Predicate createPredicateDate(CriteriaBuilder theBuilder, Root<? extends IResourceTable> from, String theParamName, IQueryParameterType theParam) {
		Predicate p;
		if (theParam instanceof DateParam) {
			DateParam date = (DateParam) theParam;
			if (!date.isEmpty()) {
				DateRangeParam range = new DateRangeParam(date);
				p = createPredicateDateFromRange(theBuilder, from, range, theParamName, theParam);
			} else {
				// From original method: TODO: handle missing date param?
				p = null;
			}
		} else if (theParam instanceof DateRangeParam) {
			DateRangeParam range = (DateRangeParam) theParam;
			p = createPredicateDateFromRange(theBuilder, from, range, theParamName, theParam);
		} else {
			throw new IllegalArgumentException("Invalid token type: " + theParam.getClass());
		}
		return p;
	}
	
	protected Predicate createPredicateDateFromRange(CriteriaBuilder theBuilder, Root<? extends IResourceTable> from, DateRangeParam theRange, String theParamName, IQueryParameterType theParam) {
		Calendar c = Calendar.getInstance();
		Date lowerBound = theRange.getLowerBoundAsInstant();
		Date upperBound = theRange.getUpperBoundAsInstant();

		Predicate lb = null;
		if (lowerBound != null) {
			lb = translatePredicateDateGreaterThan(theParamName, lowerBound, from, theBuilder); 
		}

		Predicate ub = null;
		if (upperBound != null) {
			ub = translatePredicateDateLessThan(theParamName, upperBound, from, theBuilder); 
		}

		if (lb != null && ub != null) {
			return (theBuilder.and(lb, ub));
		} else if (lb != null) {
			return (lb);
		} else {
			return (ub);
		}
	}
	
	public abstract Predicate translatePredicateDateLessThan(String theParamName, Date upperBound, Root<? extends IResourceTable> from, CriteriaBuilder theBuilder);
	public abstract Predicate translatePredicateDateGreaterThan(String theParamName, Date lowerBound, Root<? extends IResourceTable> from, CriteriaBuilder theBuilder);
	
	protected Set<Long> addPredicateParamMissing(Set<Long> thePids, String joinName, String theParamName, Class<? extends BaseResourceIndexedSearchParam> theParamTable) {
		String resourceType = getContext().getResourceDefinition(getResourceType()).getName();

		CriteriaBuilder builder = myEntityManager.getCriteriaBuilder();
		CriteriaQuery<Long> cq = builder.createQuery(Long.class);
		Root<ResourceTable> from = cq.from(ResourceTable.class);
		cq.select(from.get("myId").as(Long.class));

		Subquery<Long> subQ = cq.subquery(Long.class);
		Root<? extends BaseResourceIndexedSearchParam> subQfrom = subQ.from(theParamTable); 
		subQ.select(subQfrom.get("myResourcePid").as(Long.class));
		Predicate subQname = builder.equal(subQfrom.get("myParamName"), theParamName);
		Predicate subQtype = builder.equal(subQfrom.get("myResourceType"), resourceType);
		subQ.where(builder.and(subQtype, subQname));

		Predicate joinPredicate = builder.not(builder.in(from.get("myId")).value(subQ));
		Predicate typePredicate = builder.equal(from.get("myResourceType"), resourceType);
		
		if (thePids.size() > 0) {
			Predicate inPids = (from.get("myId").in(thePids));
			cq.where(builder.and(inPids, typePredicate, joinPredicate));
		} else {
			cq.where(builder.and(typePredicate, joinPredicate));
		}
		
		ourLog.info("Adding :missing qualifier for parameter '{}'", theParamName);
		
		TypedQuery<Long> q = myEntityManager.createQuery(cq);
		List<Long> resultList = q.getResultList();
		HashSet<Long> retVal = new HashSet<Long>(resultList);
		return retVal;
	}
	
	protected boolean addPredicateMissingFalseIfPresent(CriteriaBuilder theBuilder, String theParamName, Root<? extends IResourceTable> from, List<Predicate> codePredicates, IQueryParameterType nextOr) {
		boolean missingFalse = false;
		if (nextOr.getMissing() != null) {
			if (nextOr.getMissing().booleanValue() == true) {
				throw new InvalidRequestException(getContext().getLocalizer().getMessage(BaseFhirResourceDao.class, "multipleParamsWithSameNameOneIsMissingTrue", theParamName));
			}
			Predicate singleCode = from.get("myId").isNotNull();
			Predicate name = theBuilder.equal(from.get("myParamName"), theParamName);
			codePredicates.add(theBuilder.and(name, singleCode));
			missingFalse = true;
		}
		return missingFalse;
	}

}