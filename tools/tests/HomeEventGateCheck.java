import com.example.tvdashboard.boot.HomeEventGate;
import java.util.Collections;
import java.util.Set;
public class HomeEventGateCheck {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        HomeEventGate g = new HomeEventGate();
        Set<String> targets = Collections.singleton("vendor.home/vendor.home.Main");
        check(!g.accept(false,true,false,targets,"vendor.home","vendor.home.Main",0));
        check(!g.accept(true,false,false,targets,"vendor.home","vendor.home.Main",0));
        check(!g.accept(true,true,true,targets,"vendor.home","vendor.home.Main",0));
        check(!g.accept(true,true,false,targets,"vendor.home","vendor.home.Settings",0));
        check(!g.accept(true,true,false,targets,"other.app","vendor.home.Main",0));
        check(!g.accept(true,true,false,targets,null,null,0));
        check(g.accept(true,true,false,targets,"vendor.home","vendor.home.Main",0));
        check(!g.accept(true,true,false,targets,"vendor.home","vendor.home.Main",1999));
        check(g.accept(true,true,false,targets,"vendor.home","vendor.home.Main",2000));
        check(!g.accept(true,true,false,Collections.emptySet(),"vendor.home","vendor.home.Main",5000));
        System.out.println("PASS: 10 event gate checks");
    }
}
