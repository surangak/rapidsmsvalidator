<spring:htmlEscape defaultHtmlEscape="true" />
<ul id="menu">
	<li class="first"><a
		href="${pageContext.request.contextPath}/admin"><spring:message
				code="admin.title.short" /></a></li>

	<li
		<c:if test='<%= request.getRequestURI().contains("/regenrateRapidSMSMessages") %>'>class="active"</c:if>>
		<a
		href="${pageContext.request.contextPath}/module/rapidsmsvalidator/regenrateRapidSMSMessages.form"><spring:message
				code="rapidsmsvalidator.regenrateRapidSMSMessages" /></a>
				
				<a
		href="${pageContext.request.contextPath}/module/rapidsmsvalidator/voidRiskAndMatEncounters.form"><spring:message
				code="rapidsmsvalidator.voidRiskAndMatEncounters" /></a>	
				
				<a
		href="${pageContext.request.contextPath}/module/rapidsmsvalidator/editBirthEncounters.form"><spring:message
				code="rapidsmsvalidator.editBirthEncounters" /></a>
				
					

	</li>
	
	<!-- Add further links here -->
</ul>
<h2>
	<spring:message code="rapidsmsvalidator.title" />
</h2>
