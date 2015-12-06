package fr.lteconsulting.pomexplorer.commands;

import java.util.HashSet;
import java.util.Set;

import fr.lteconsulting.pomexplorer.Log;
import fr.lteconsulting.pomexplorer.Project;
import fr.lteconsulting.pomexplorer.Tools;
import fr.lteconsulting.pomexplorer.Session;
import fr.lteconsulting.pomexplorer.change.Change.ChangeCause;
import fr.lteconsulting.pomexplorer.change.graph.GraphChange;
import fr.lteconsulting.pomexplorer.change.graph.GraphChange.GavChange;
import fr.lteconsulting.pomexplorer.change.graph.GraphChange.RelationChange;
import fr.lteconsulting.pomexplorer.change.graph.GraphChangeProcessing;
import fr.lteconsulting.pomexplorer.change.project.ProjectChange;
import fr.lteconsulting.pomexplorer.model.Gav;

public class ChangeCommand
{
	@Help( "lists the change set" )
	public void list( Session session, Log log )
	{
		if( session.graphChanges().isEmpty() )
		{
			log.html( "empty graph changeset<br/>" );
		}
		else
		{
			log.html( "session's graph changeset:<br/>" );
			StringBuilder sb = new StringBuilder();
			session.graphChanges().stream().sorted( ( a, b ) -> Gav.alphabeticalComparator.compare( a.getSource(), b.getSource() ) ).forEach( c -> {
				Set<ChangeCause> causes = c.getCauses();

				sb.append( c );
				if( causes != null && !causes.isEmpty() )
				{
					sb.append( "<span style='color:grey;font-size:90%;'>" );
					causes.forEach( ca -> sb.append( " processor:" + ca.getProcessor() + " " + ca.getChange() ) );
					sb.append( "</span>" );
				}
				sb.append( "<br/>" );
			} );
			log.html( sb.toString() );
		}

		if( session.projectChanges().isEmpty() )
		{
			log.html( "empty project changeset<br/>" );
		}
		else
		{
			log.html( "session's project changeset:<br/>" );
			StringBuilder sb = new StringBuilder();
			session.projectChanges().stream().sorted( ( a, b ) -> Project.alphabeticalComparator.compare( a.getProject(), b.getProject() ) ).forEach( c -> {
				Set<ChangeCause> causes = c.getCauses();

				sb.append( c );
				if( causes != null && !causes.isEmpty() )
				{
					sb.append( "<span style='color:grey;font-size:90%;'>" );
					causes.forEach( ca -> sb.append( " processor:" + ca.getProcessor() + " " + ca.getChange() ) );
					sb.append( "</span>" );
				}
				sb.append( "<br/>" );
			} );
		}
	}

	@Help( "process the graph changes with active processors (propagator, releaser, opener)" )
	public void processChanges( Session session, Log log )
	{
		log.html( "Processing changes...<br/>" );
		GraphChangeProcessing processing = new GraphChangeProcessing();
		Set<GraphChange> processed = processing.process( session, log, session.graphChanges() );
		processed.forEach( change -> session.graphChanges().add( change ) );
		log.html( "Done !<br/>Use the 'change list' command to see the new changesets<br/>" );
	}

	@Help( "resolve the current graph changeset. That is all the graph changes are converted into project changes and injected in the project changeset." )
	public void resolveChanges( Session session, Log log )
	{
		// TODO : resolveChanges
		// TODO : project details : show where dependencies are defined

		Set<GraphChange> changes = new HashSet<>( session.graphChanges() );
		session.graphChanges().clear();

		log.html( "resolving " + changes.size() + " graph changes...<br/>" );
		log.html( "<i>project changes will be generated from those graph structure changes</i><br/>" );

		for( GraphChange change : changes )
		{
			Project changedProject = session.projects().forGav( change.getSource() );
			if( changedProject == null )
			{
				log.html( Tools.warningMessage( "no project for gav " + change.getSource() + ", change cannot be resolved (" + change + ")." ) );
				continue;
			}

			if( change instanceof GavChange )
			{
				Gav newValue = change.getNewValue();

				// TODO same kind of processor system :
				// - change something which is defined as a property => change the proerty value instead
				// - change a project's version when it is defined by the parent's version => change the parent version.
				// - same for groupId...

				// the most simple one : change the project's gav
				// TODO : if groupId is defined by the parent, change the parent instead
				session.projectChanges().add( ProjectChange.set( changedProject, "project", "groupId", newValue.getGroupId() ) );
				session.projectChanges().add( ProjectChange.set( changedProject, "project", "artifactId", newValue.getArtifactId() ) );
				// TODO : if the version is defined by the parent, change the parent version instead
				session.projectChanges().add( ProjectChange.set( changedProject, "project", "version", newValue.getVersion() ) );
			}
		}
	}

	@Help( "applies the project changes in the pom.xml files" )
	public void apply()
	{
	}

	@Help( "changes a gav in the graph" )
	public void gav( Session session, Log log, Gav gav, Gav newGav )
	{
		GavChange change = new GavChange( gav, newGav );
		session.graphChanges().add( change );
		log.html( "added change in change set: " + change + "<br/>" );
	}

	@Help( "changes a relation in the graph" )
	public void relation( Session session, Log log, Gav source, String gact, Gav newTarget )
	{
		RelationChange change = RelationChange.create( source, gact, newTarget );
		if( change == null )
		{
			log.html( Tools.warningMessage( "error creating the change !" ) );
		}
		else
		{
			session.graphChanges().add( change );
			log.html( "added change in change set: " + change + "<br/>" );
		}
	}

	@Help( "removes a relation from the graph" )
	public void removeRelation( Session session, Log log, Gav source, String gact )
	{
		RelationChange change = RelationChange.create( source, gact, null );
		if( change == null )
		{
			log.html( Tools.warningMessage( "error creating the change !" ) );
		}
		else
		{
			session.graphChanges().add( change );
			log.html( "added change in change set: " + change + "<br/>" );
		}
	}

	@Help( "sets or adds a dependency to a project" )
	public void setProject( Session session, Log log, FilteredGAVs gavs, String location, String nodeName, String newValue )
	{
		for( Gav gav : gavs.getGavs( session ) )
		{
			Project project = session.projects().forGav( gav );
			if( project == null )
			{
				log.html( "no project for gav " + gav + "<br/>" );
				continue;
			}

			ProjectChange change = ProjectChange.set( project, location, nodeName, newValue );
			if( change == null )
			{
				log.html( "no change added<br/>" );
				return;
			}

			session.projectChanges().add( change );
			log.html( "added change in change set: " + change + "<br/>" );
		}

	}

	@Help( "removes a dependency from a project" )
	public void removeProject( Session session, Log log, FilteredGAVs gavs, String location, String nodeName )
	{
		for( Gav gav : gavs.getGavs( session ) )
		{
			Project project = session.projects().forGav( gav );
			if( project == null )
			{
				log.html( "no project for gav " + gav + "<br/>" );
				continue;
			}

			ProjectChange change = ProjectChange.remove( project, location, nodeName );
			if( change == null )
			{
				log.html( "no change added<br/>" );
				return;
			}

			session.projectChanges().add( change );
			log.html( "added change in change set: " + change + "<br/>" );
		}
	}
}
