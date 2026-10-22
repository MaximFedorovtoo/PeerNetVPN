package org.uzel.desktop;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class DesktopApplicationsTest {
    @Test public void selectedRealExecutablesPersistCanonicalPathsDeduplicateAndAllowClear()throws Exception {
        Path root=Files.createTempDirectory("desktop-applications-"),folder=Files.createDirectories(root.resolve("Программы"));
        Path exe=Files.write(folder.resolve("Browser.EXE"),new byte[]{1});
        DesktopApplications.save(root,List.of(exe,folder.resolve("..").resolve("Программы").resolve("Browser.EXE")));
        assertEquals(List.of(exe.toRealPath()),DesktopApplications.load(root));
        DesktopApplications.save(root,List.of());assertTrue(DesktopApplications.load(root).isEmpty());
        try(var files=Files.list(root)){assertFalse(files.anyMatch(p->p.getFileName().toString().endsWith(".tmp")));}
    }
    @Test public void rejectsMissingNonExecutableDirectoriesCarrierAndAmbiguousProcessNames()throws Exception {
        Path root=Files.createTempDirectory("desktop-app-validation-");
        for(String name:List.of("missing.exe","readme.txt","java.exe","JAVAW.EXE","PeerNet.exe","Uzelio.exe","customUZELIO.exe")){
            Path path=root.resolve(name);if(!name.equals("missing.exe"))Files.write(path,new byte[]{1});
            assertThrows(name,IOException.class,()->DesktopApplications.save(root,List.of(path)));
        }
        Path directory=Files.createDirectory(root.resolve("Folder.exe"));assertThrows(IOException.class,()->DesktopApplications.save(root,List.of(directory)));
        Path first=Files.write(Files.createDirectory(root.resolve("first")).resolve("Browser.exe"),new byte[]{1});
        Path second=Files.write(Files.createDirectory(root.resolve("second")).resolve("BROWSER.EXE"),new byte[]{1});
        assertThrows(IOException.class,()->DesktopApplications.save(root,List.of(first,second)));
        List<Path> tooMany=new ArrayList<>();for(int i=0;i<65;i++)tooMany.add(Files.write(root.resolve("app"+i+".exe"),new byte[]{1}));
        assertThrows(IOException.class,()->DesktopApplications.save(root,tooMany));
    }
    @Test public void corruptSelectionFailsClosedAndFailedSavePreservesPreviousFile()throws Exception {
        Path root=Files.createTempDirectory("desktop-app-corrupt-"),exe=Files.write(root.resolve("Browser.exe"),new byte[]{1});
        DesktopApplications.save(root,List.of(exe));byte[] saved=Files.readAllBytes(root.resolve("applications.properties"));
        assertThrows(IOException.class,()->DesktopApplications.save(root,List.of(root.resolve("gone.exe"))));
        assertArrayEquals(saved,Files.readAllBytes(root.resolve("applications.properties")));
        Files.delete(exe);assertThrows(IOException.class,()->DesktopApplications.load(root));
        Files.writeString(root.resolve("applications.properties"),"count=1000000\n");assertThrows(IOException.class,()->DesktopApplications.load(root));
    }
    @Test public void inventoryDecodesOnlyRealAppsAndGroupsWindowsProcessBasenameCollisions()throws Exception {
        Path root=Files.createTempDirectory("desktop-app-inventory-"),one=Files.createDirectory(root.resolve("one")),two=Files.createDirectory(root.resolve("two"));
        Path browser=Files.write(one.resolve("Browser.exe"),new byte[]{1}),other=Files.write(two.resolve("BROWSER.EXE"),new byte[]{1});
        Path carrier=Files.write(root.resolve("javaw.exe"),new byte[]{1});
        List<DesktopApplications.App> apps=DesktopApplications.decodeInventory(List.of(line("Браузер",browser),line("Other browser",other),line("Runtime",carrier),"malformed",line("Gone",root.resolve("gone.exe"))));
        assertEquals(1,apps.size());assertEquals("browser.exe",apps.get(0).processName());
        assertTrue(apps.get(0).name().contains("browser.exe"));
        assertTrue(Files.isRegularFile(apps.get(0).executable()));
    }
    private static String line(String name,Path path){return Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8))+"\t"+Base64.getEncoder().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));}
}
