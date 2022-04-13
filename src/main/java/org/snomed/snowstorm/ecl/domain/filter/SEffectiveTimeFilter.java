package org.snomed.snowstorm.ecl.domain.filter;

import org.snomed.langauges.ecl.domain.filter.EffectiveTimeFilter;
import org.snomed.langauges.ecl.domain.filter.NumericComparisonOperator;

import java.util.Set;

public class SEffectiveTimeFilter extends EffectiveTimeFilter {
	public SEffectiveTimeFilter(NumericComparisonOperator operator, Set<Integer> effectiveTimes) {
		super(operator, effectiveTimes);
	}

	public void toString(StringBuffer buffer) {
		buffer.append(" effectiveTime ").append(getOperator().getText()).append(" ");
		ECLToStringUtil.toString(buffer, getEffectiveTime());
	}
}
