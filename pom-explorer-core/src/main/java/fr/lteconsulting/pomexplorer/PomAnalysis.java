package fr.lteconsulting.pomexplorer;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import fr.lteconsulting.pomexplorer.graph.PomGraph.PomGraphWriteTransaction;
import fr.lteconsulting.pomexplorer.graph.relation.BuildDependencyRelation;
import fr.lteconsulting.pomexplorer.graph.relation.DependencyRelation;
import fr.lteconsulting.pomexplorer.graph.relation.ParentRelation;
import fr.lteconsulting.pomexplorer.graph.relation.Scope;
import fr.lteconsulting.pomexplorer.model.Dependency;
import fr.lteconsulting.pomexplorer.model.DependencyKey;
import fr.lteconsulting.pomexplorer.model.Gav;
import fr.lteconsulting.pomexplorer.model.transitivity.RawDependency;

/**
 * Performs an analysis of pom files by batch
 * and enter them in the graph of a session for
 * further analysis
 */
public class PomAnalysis
{
	private final static Set<String> IGNORED_DIRS = new HashSet<>( Arrays.asList(
			"target",
			"bin",
			"src",
			"node_modules",
			".git",
			"war",
			"gwt-unitCache",
			".idea",
			".settings" ) );

    private final Set<String> ignoredDirs;
	private final Session session;
	private final PomFileLoader pomFileLoader;
	private final Log log;
	private final boolean verbose;
	private final Map<String, Profile> profiles;

	private final List<File> pomFiles = new ArrayList<>();
	private final Set<Project> loadedProjects = new HashSet<>();
	private final Set<Project> completedProjects = new HashSet<>();
	private final Set<Project> unresolvableProjects = new HashSet<>();
	private final Set<Project> duplicatedProjects = new HashSet<>();

	private final ProjectContainer projects;

   

	public static void runFullRecursiveAnalysis( String directory, Session session, PomFileLoader pomFileLoader, String[] profilesId, boolean verbose, Log log )
	{
		log.html( "analyzing '" + directory + "'<br/>" );

		long duration = System.currentTimeMillis();

		PomAnalysis analyzis = new PomAnalysis( session, pomFileLoader, profilesId, verbose, log );
		analyzis.addDirectory( directory );
		Set<Project> loadedProjects = analyzis.loadProjects();
		analyzis.completeLoadedProjects();
		analyzis.addCompletedProjectsToSession();
		Set<Project> addedToGraph = analyzis.addCompletedProjectsToGraph();

		duration = System.currentTimeMillis() - duration;

		if( !analyzis.getDuplicatedProjects().isEmpty() )
		{
			StringBuilder sb = new StringBuilder();
			sb.append( "<br/>" + analyzis.getDuplicatedProjects().size() + " duplicated projects:<br/>" );
			sb.append( "<ul>" );
			analyzis.getDuplicatedProjects().stream().sorted( Project.alphabeticalComparator ).forEach( project -> sb.append( "<li>" + project + "</li>" ) );
			sb.append( "</ul>" );
			log.html( Tools.warningMessage( sb.toString() ) );
		}

		if( !analyzis.getUnresolvableProjects().isEmpty() )
		{
			StringBuilder sb = new StringBuilder();
			sb.append( "<br/>" + analyzis.getUnresolvableProjects().size() + " unresolvable projects:<br/>" );
			sb.append( "<ul>" );
			analyzis.getUnresolvableProjects().stream().sorted( Project.alphabeticalComparator ).forEach( project -> sb.append( "<li>" + project + "</li>" ) );
			sb.append( "</ul>" );
			log.html( Tools.warningMessage( sb.toString() ) );
		}

		log.html( "<br/>analysis report:<br/>"
				+ loadedProjects.size() + " projects loaded and added to the pom graph,<br/>"
				+ addedToGraph.size() + " projects added to graph,<br/>"
				+ "in " + duration + " ms.<br/>" );
	}

	public PomAnalysis( Session session, PomFileLoader pomFileLoader, String[] profilesId, boolean verbose, Log log )
	{
		this.session = session;
		this.ignoredDirs = new HashSet<>(IGNORED_DIRS);
		ignoredDirs.addAll(session.getIgnoredDirs());
		this.pomFileLoader = pomFileLoader;
		this.log = log;
		this.verbose = verbose;

		if( profilesId != null )
		{
			profiles = new HashMap<>();
			for( int i = 0; i < profilesId.length; i++ )
				profiles.put( profilesId[i], new Profile( profilesId[i] ) );
		}
		else
		{
			profiles = null;
		}

		projects = session
				.projects()
				.combine( gav -> loadedProjects.stream()
						.filter( p -> p.getGav().equals( gav ) )
						.findFirst()
						.orElse( null ) )
				.combine( gav -> completedProjects.stream()
						.filter( p -> p.getGav().equals( gav ) )
						.findFirst()
						.orElse( null ) );

		log.html( "Pom Analysis ready!" );
	}

	public Set<Project> getUnresolvableProjects()
	{
		return unresolvableProjects;
	}

	public Set<Project> getDuplicatedProjects()
	{
		return duplicatedProjects;
	}

	public Set<File> addDirectory( String directory )
	{
		log.html( "adding directory '" + directory + "'<br/>" );

		File file = new File( directory );
		if( !file.exists() )
		{
			log.html( Tools.errorMessage( "'" + directory + "' does not exist !" ) );
			return null;
		}

		Set<File> foundFiles = scanPomFiles( file );

		pomFiles.addAll( foundFiles );

		log.html( Tools.logMessage( "found " + foundFiles.size() + " pom files" ) );

		return foundFiles;
	}

	public File addFile( File file )
	{
		log.html( "adding file '" + file + "'<br/>" );

		if( !file.exists() )
		{
			log.html( Tools.errorMessage( "'" + file + "' does not exist !" ) );
			return null;
		}

		pomFiles.add( file );

		return file;
	}

	public Set<Project> loadProjects()
	{
		log.html( Tools.logMessage( "loading pom files" ) );

		Set<Project> loadedProjects = new HashSet<>();

		while( !pomFiles.isEmpty() )
		{
			File pomFile = pomFiles.remove( 0 );

			Project project = loadProject( pomFile, false );
			if( project != null )
			{
				loadedProjects.add( project );
				this.loadedProjects.add( project );
			}
		}

		log.html( "loaded " + loadedProjects.size() + " projects<br/><br/>" );
		if( verbose )
		{
			log.html( "<br/>loaded projects:<br/>" );
			loadedProjects.stream().sorted( Project.alphabeticalComparator ).forEach( ( Project p ) -> log.html( p + "<br/>" ) );
		}

		return loadedProjects;
	}

	public Set<Project> completeLoadedProjects()
	{
		log.html( Tools.logMessage( "completing loaded projects" ) );

		Set<Project> readyProjects = new HashSet<>();
		Set<Project> unresolvableProjects = new HashSet<>();

		for( Project project : loadedProjects )
		{
			if( session.projects().contains( project.getGav() ) || completedProjects.stream().filter( p -> p.getGav().equals( project.getGav() ) ).findFirst().isPresent() )
			{
				Project duplicate = session.projects().forGav( project.getGav() );
				if( duplicate == null )
					duplicate = completedProjects.stream().filter( p -> p.getGav().equals( project.getGav() ) ).findFirst().get();

				duplicatedProjects.add( project );
				log.html( Tools.warningMessage( "trying to add a project which is duplicated: " + project + ", already inserted : " + duplicate ) );
			}
			else if( processProjectForCompleteness( project, pomFileLoader ) )
			{
				readyProjects.add( project );
			}
			else
			{
				unresolvableProjects.add( project );
			}
		}

		log.html( Tools.logMessage( readyProjects.size() + " ready projects and " + unresolvableProjects.size() + " unresolvable projects" ) );

		loadedProjects.clear();

		return readyProjects;
	}

	public void addCompletedProjectsToSession()
	{
		completedProjects.forEach( session.projects()::add );
	}

	public Set<Project> addCompletedProjectsToGraph()
	{
		log.html( Tools.logMessage( "adding completed projects to graph" ) );

		Set<Project> addedToGraph = new HashSet<>();

		for( Project project : completedProjects )
		{
			if( addProjectToGraph( project ) )
				addedToGraph.add( project );
			else
				log.html( Tools.errorMessage( "cannot add to graph project " + project ) );
		}

		completedProjects.clear();

		return addedToGraph;
	}

	private boolean addProjectToGraph( Project project )
	{
		PomGraphWriteTransaction tx = session.graph().write();

		tx.removeRelations( tx.relations( project.getGav() ) );

		try
		{
			Gav gav = project.getGav();
			tx.addGav( gav );

			Gav parentGav = project.getParentGav();
			if( parentGav != null )
			{
				tx.addGav( parentGav );
				tx.addRelation( new ParentRelation( gav, parentGav ) );
			}

			// add resolved local dependencies
			Map<DependencyKey, RawDependency> dependencies = project.getLocalDependencies( null, profiles, projects, log );
			if( dependencies != null )
			{
				for( Entry<DependencyKey, RawDependency> e : dependencies.entrySet() )
				{
					DependencyKey key = e.getKey();
					RawDependency rawDependency = e.getValue();

					Gav dependencyGav = new Gav( key.getGroupId(), key.getArtifactId(), rawDependency.getVs().getVersion() );
					tx.addGav( dependencyGav );
					tx.addRelation( new DependencyRelation( gav, dependencyGav, new Dependency( dependencyGav, rawDependency.getVs().getScope(), key.getClassifier(), key.getType() ) ) );
				}
			}

			// add resolved local plugin dependencies
			Set<Gav> pluginDependencies = project.getLocalPluginDependencies( profiles, projects, log );
			if( pluginDependencies != null )
			{
				for( Gav pluginGav : pluginDependencies )
				{
					tx.addGav( pluginGav );
					tx.addRelation( new BuildDependencyRelation( gav, pluginGav ) );
				}
			}

			tx.commit();

			return true;
		}
		catch( Exception e )
		{
			log.html( Tools.errorMessage( "Cannot add project " + project + " to graph. Cause: " + e.getMessage() ) );
			Tools.logStacktrace( e, log );

			return false;
		}
	}

	private boolean processProjectForCompleteness( Project project, PomFileLoader callback )
	{
		boolean complete = true;

		Set<Project> projectsToAddToReady = new HashSet<>();

		if( project.getParentGav() != null && projects.forGav( project.getParentGav() ) == null )
		{
			Project parentProject = loadAndCheckProject( project.getParentGav(), callback, project );
			if( parentProject != null )
				projectsToAddToReady.add( parentProject );
		}

		if( project.getMavenProject().getDependencyManagement() != null && project.getMavenProject().getDependencyManagement().getDependencies() != null )
		{
			for( org.apache.maven.model.Dependency d : project.getMavenProject().getDependencyManagement().getDependencies() )
			{
				if( Scope.fromString( project.interpolateValue( d.getScope(), projects, log ) ) == Scope.IMPORT && "pom".equals( d.getType() ) )
				{
					// TODO should use project's dependency management to resolve the gav when version is null (rare cases maybe)
					Gav bomGav = project.interpolateGav( new Gav( d.getGroupId(), d.getArtifactId(), d.getVersion() ), projects, log );

					if( projects.forGav( bomGav ) == null )
					{
						Project bomProject = loadAndCheckProject( bomGav, callback, project );
						if( bomProject != null )
							projectsToAddToReady.add( bomProject );
					}
				}
			}
		}

		if( complete )
		{
			completedProjects.addAll( projectsToAddToReady );
			completedProjects.add( project );
		}
		else
		{
			unresolvableProjects.add( project );
		}

		return complete;
	}

	private Project loadAndCheckProject( Gav gav, PomFileLoader callback, Project resolvedProject )
	{
		File pomFile = callback.loadPomFileForGav( gav, null, log );
		if( pomFile == null )
		{
			log.html( Tools.errorMessage( "cannot resolve project " + resolvedProject + " due to:<br/>&nbsp;&nbsp;&nbsp;missing bom import " + gav ) );
			return null;
		}

		Project project = loadProject( pomFile, true );

		if( project != null && processProjectForCompleteness( project, callback ) )
			return project;

		log.html( Tools.errorMessage( "cannot resolve project " + resolvedProject + " due to:<br/>&nbsp;&nbsp;&nbsp;missing bom import " + gav ) );
		return null;
	}

	private Set<File> scanPomFiles( File startFile )
	{
		assert startFile != null && startFile.exists();

		Set<File> pomFiles = new HashSet<>();

		List<File> queue = new ArrayList<>();
		queue.add( startFile );

		while( !queue.isEmpty() )
		{
			File file = queue.remove( 0 );

			if( file.isDirectory() )
			{
				if( !acceptedDir( file ) )
					continue;

				try( DirectoryStream<Path> pathStream = Files.newDirectoryStream( file.toPath(), this::acceptedPath ) )
				{
					pathStream.forEach( path -> queue.add( new File( path.toString() ) ) );
				}
				catch( IOException e )
				{
					e.printStackTrace();
				}
			}
			else if( file.getName().equalsIgnoreCase( "pom.xml" ) || file.getName().endsWith( ".pom" ) )
			{
				pomFiles.add( file );
			}
		}

		return pomFiles;
	}

	private boolean acceptedDir( File file )
	{
		for( String ignored : ignoredDirs )
			if( ignored.equalsIgnoreCase( file.getName() ) || new File(ignored).equals( file ) )
				return false;
		return true;
	}

	private boolean acceptedPath( Path path )
	{
		String pathName = path.getFileName().toString();

		return (Files.isDirectory( path ) && acceptedDir( path.toFile() )) || (pathName.endsWith( ".pom" ) || "pom.xml".equalsIgnoreCase( pathName ));
	}

	private Project loadProject( File pomFile, boolean isExternal )
	{
		try
		{
			Project project = new Project( pomFile, isExternal );
			project.readPomFile();

			return project;
		}
		catch( Exception e )
		{
			log.html( Tools.errorMessage( "error loading pom file " + pomFile.getAbsolutePath() + ", message: " + e.getMessage() ) );

			return null;
		}
	}
}
