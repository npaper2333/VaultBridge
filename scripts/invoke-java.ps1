# Dot-source this helper. It does not alter PATH, the registry, system encoding or keys.
function Resolve-VaultBridgeJdk {
    [CmdletBinding()]
    param([Parameter(Mandatory=$true)][string]$ProjectRoot,[string]$JdkDirectory)
    if (-not $JdkDirectory) {
        $taskCandidate = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'tools') -Directory | Where-Object Name -Like 'jdk-17*' | Select-Object -First 1
        if (-not $taskCandidate) { throw 'Portable JDK missing. Pass -JdkDirectory with an existing JDK directory.' }
        $JdkDirectory = $taskCandidate.FullName
    }
    $taskResolvedJdk = (Resolve-Path -LiteralPath $JdkDirectory -ErrorAction Stop).Path
    if ($taskResolvedJdk -match '[^\x00-\x7f]') { throw 'This Windows JDK launcher requires an ASCII runtime path. Pass -JdkDirectory with an existing portable JDK in an ASCII directory; no global installation or PATH change is needed.' }
    foreach ($taskTool in @('java.exe','javac.exe')) { if (-not (Test-Path -LiteralPath (Join-Path $taskResolvedJdk ('bin\'+$taskTool)) -PathType Leaf)) { throw "Portable JDK tool missing: $taskTool" } }
    return $taskResolvedJdk
}

function ConvertTo-VaultBridgeJavaArgument {
    param([AllowEmptyString()][string]$Value)
    if ($null -eq $Value -or $Value.IndexOf([char]0) -ge 0 -or $Value.IndexOf("`r") -ge 0 -or $Value.IndexOf("`n") -ge 0) { throw 'Java arguments must not contain NUL or line breaks.' }
    # Absolute Windows paths and classpath/property path lists are portable with /.
    # Other text preserves literal backslashes through the argfile escape grammar.
    if ($Value -match '(?:^|;|=)[A-Za-z]:[\\/]' -or $Value.StartsWith('\\')) { $Value = $Value.Replace('\','/') }
    return '"'+$Value.Replace('\','\\').Replace('"','\"')+'"'
}

function Get-VaultBridgeUtf8Bridge {
    param([string]$JdkDirectory,[string]$ArgumentDirectory)
    if (-not (Get-Variable -Name VaultBridgeUtf8Bridges -Scope Script -ErrorAction SilentlyContinue)) { $script:VaultBridgeUtf8Bridges = @{} }
    $taskBridgeKey = $JdkDirectory+'|'+$ArgumentDirectory
    if ($script:VaultBridgeUtf8Bridges.ContainsKey($taskBridgeKey)) { return $script:VaultBridgeUtf8Bridges[$taskBridgeKey] }
    $taskBridgeDirectory = Join-Path $ArgumentDirectory ('utf8-bridge-'+[Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $taskBridgeDirectory | Out-Null
    # Windows java.exe may decode its native @file before file.encoding applies.
    # Only the ASCII bridge launch crosses that boundary; real app argv is read here.
    $taskBridgeSource = @'
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.jar.*;
public final class VaultBridgeUtf8Launcher {
  static String decode(String value) throws IOException {
    if(value.length()<2||value.charAt(0)!='"'||value.charAt(value.length()-1)!='"')throw new IOException("Invalid quoted UTF8 argument");
    StringBuilder result=new StringBuilder();
    for(int i=1;i<value.length()-1;i++) {
      char c=value.charAt(i);
      if(c=='\\') { if(++i>=value.length()-1)throw new IOException("Incomplete argument escape");c=value.charAt(i);if(c!='\\'&&c!='"')throw new IOException("Unsupported argument escape"); }
      else if(c=='"')throw new IOException("Unescaped argument quote");
      if(c==0||c=='\r'||c=='\n')throw new IOException("Control character in argument");result.append(c);
    }
    return result.toString();
  }
  public static void main(String[] input) throws Throwable {
    if(input.length!=1)throw new IOException("Expected one UTF8 argument file");
    List<String> values=new ArrayList<String>();for(String line:Files.readAllLines(Paths.get(input[0]),StandardCharsets.UTF_8))values.add(decode(line));
    String main,classpath;int first;
    if(values.size()>=3&&(values.get(0).equals("-cp")||values.get(0).equals("-classpath"))) {classpath=values.get(1);main=values.get(2);first=3;}
    else if(values.size()>=2&&values.get(0).equals("-jar")) {
      File jar=new File(values.get(1));if(!jar.isFile())throw new IOException("Application JAR missing");
      try(JarFile archive=new JarFile(jar)){Manifest manifest=archive.getManifest();main=manifest==null?null:manifest.getMainAttributes().getValue("Main-Class");}
      classpath=jar.getPath();first=2;
    } else throw new IOException("Only project -cp/-classpath/-jar launches are supported");
    if(main==null||!main.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))throw new IOException("Invalid application main class");
    List<URL> urls=new ArrayList<URL>();for(String part:classpath.split(File.pathSeparator,-1)){File file=new File(part);if(part.isEmpty()||!file.exists())throw new IOException("Classpath entry missing");urls.add(file.toURI().toURL());}
    Thread thread=Thread.currentThread();ClassLoader previous=thread.getContextClassLoader();String previousPath=System.getProperty("java.class.path");
    try(URLClassLoader loader=new URLClassLoader(urls.toArray(new URL[0]),VaultBridgeUtf8Launcher.class.getClassLoader())) {
      thread.setContextClassLoader(loader);System.setProperty("java.class.path",classpath);
      try {Class<?> type=Class.forName(main,true,loader);Method method=type.getMethod("main",String[].class);if(!Modifier.isStatic(method.getModifiers())||method.getReturnType()!=Void.TYPE)throw new IOException("Invalid main method");method.invoke(null,(Object)values.subList(first,values.size()).toArray(new String[0]));}
      catch(InvocationTargetException error){throw error.getCause();}
      finally{thread.setContextClassLoader(previous);System.setProperty("java.class.path",previousPath);}
    }
  }
}
'@
    $taskBridgeFile = Join-Path $taskBridgeDirectory 'VaultBridgeUtf8Launcher.java'
    [IO.File]::WriteAllText($taskBridgeFile,$taskBridgeSource,[Text.UTF8Encoding]::new($false))
    Invoke-VaultBridgeJava -JdkDirectory $JdkDirectory -Tool javac -ArgumentDirectory $taskBridgeDirectory -Arguments @('--release','8','-encoding','UTF-8','-d',$taskBridgeDirectory,$taskBridgeFile)
    $script:VaultBridgeUtf8Bridges[$taskBridgeKey] = $taskBridgeDirectory
    return $taskBridgeDirectory
}

function Invoke-VaultBridgeJava {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string]$JdkDirectory,
        [ValidateSet('java','javac')][string]$Tool='java',
        [Parameter(Mandatory=$true)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Arguments,
        [string]$ArgumentDirectory
    )
    if (-not $ArgumentDirectory) { $ArgumentDirectory = Join-Path ([IO.Path]::GetTempPath()) 'VaultBridge-java-args' }
    $taskArgumentDirectory = [IO.Path]::GetFullPath($ArgumentDirectory)
    if ($taskArgumentDirectory -match '[^\x00-\x7f]') { throw 'Java argument files need an ASCII directory. Supply an ASCII ArgumentDirectory or temporary directory.' }
    $taskJavaExecutable = Join-Path $JdkDirectory ('bin\'+$Tool+'.exe')
    if (-not (Test-Path -LiteralPath $taskJavaExecutable -PathType Leaf) -or $taskJavaExecutable -match '[^\x00-\x7f]') { throw 'Java executable missing or not in an ASCII portable JDK directory.' }
    New-Item -ItemType Directory -Path $taskArgumentDirectory -Force | Out-Null
    $taskArgumentFile = Join-Path $taskArgumentDirectory ($Tool+'-'+[Guid]::NewGuid().ToString('N')+'.args')
    $taskJvmArguments = @();$taskFileArguments = @();$taskJavaAppStarted = $false
    foreach ($taskArgument in $Arguments) {
        if ($null -eq $taskArgument) { throw 'Java arguments must not contain null entries.' }
        if ($Tool -eq 'javac' -and $taskArgument.StartsWith('-J',[StringComparison]::Ordinal)) {
            if ($taskArgument -ne '-J-Dfile.encoding=UTF-8') { $taskJvmArguments += $taskArgument }
        } elseif ($Tool -eq 'java' -and -not $taskJavaAppStarted -and $taskArgument -match '^-Xmx[1-9][0-9]*[kKmMgG]?$') { $taskJvmArguments += $taskArgument }
        elseif ($Tool -eq 'java' -and -not $taskJavaAppStarted -and $taskArgument -eq '-Dfile.encoding=UTF-8') { }
        else {
            if ($Tool -eq 'java' -and -not $taskJavaAppStarted -and $taskArgument -notin @('-cp','-classpath','-jar')) { throw 'Only project -cp/-classpath/-jar launches and ASCII -Xmx options are supported.' }
            $taskJavaAppStarted = $true;$taskFileArguments += ConvertTo-VaultBridgeJavaArgument -Value $taskArgument
        }
    }
    [IO.File]::WriteAllLines($taskArgumentFile,[string[]]$taskFileArguments,[Text.UTF8Encoding]::new($false))
    Write-Verbose ('Retained Java argument file: '+$taskArgumentFile)
    # javac's -J options belong on its launcher command line, outside @files.
    if ($Tool -eq 'javac') { & $taskJavaExecutable @taskJvmArguments '-J-Dfile.encoding=UTF-8' ('@'+$taskArgumentFile) }
    else { $taskBridge = Get-VaultBridgeUtf8Bridge -JdkDirectory $JdkDirectory -ArgumentDirectory $taskArgumentDirectory;& $taskJavaExecutable '-Dfile.encoding=UTF-8' @taskJvmArguments '-cp' $taskBridge 'VaultBridgeUtf8Launcher' $taskArgumentFile }
    if ($LASTEXITCODE -ne 0) { throw "Portable $Tool failed (exit=$LASTEXITCODE). Argument file retained at $taskArgumentFile" }
}
