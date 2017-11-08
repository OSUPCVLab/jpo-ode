package us.dot.its.jpo.ode.plugin.j2735.builders;

import java.math.BigDecimal;

public class OffsetLLB24Builder {

   private OffsetLLB24Builder() {
      throw new UnsupportedOperationException();
   }

   public static Long offsetLLB24(BigDecimal offset) {
      return offset.scaleByPowerOfTen(7).longValue();
   }
}
