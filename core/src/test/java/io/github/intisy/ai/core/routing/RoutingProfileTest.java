package io.github.intisy.ai.core.routing;
import org.junit.jupiter.api.Test;
import java.util.*; import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
class RoutingProfileTest {
  @Test void isValidAcceptsCompleteRejectsPartial() {
    RoutingProfile p = new RoutingProfile();
    p.configFile="x.json"; p.routingKey="providerRouting"; p.tierSourceProvider="claude-code";
    p.tierOrder=List.of("opus"); p.tierFallback=List.of("opus"); p.tierRegex=Pattern.compile("^claude-([a-z]+)-\\d");
    p.envPrefix="ANTHROPIC"; p.defaultContext=200000; p.defaultOutput=64000;
    p.nativeRateLimit = info -> { RoutingProfile.Synth s=new RoutingProfile.Synth(); s.status=429; s.headers=new HashMap<>(); s.body="{}"; return s; };
    assertTrue(RoutingProfile.isValid(p));
    RoutingProfile bad = p.copy(); bad.configFile="";
    assertFalse(RoutingProfile.isValid(bad));
  }
}
