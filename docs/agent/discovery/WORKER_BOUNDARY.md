# Artifact Discovery Worker Boundary

Workers do not own Artifact Discovery routing.

A Worker may report a discovery finding when it encounters an unmapped or orphaned artifact, but the Worker should not create broad backfill tasks or cleanup decisions directly.

Dispatcher, Integrator, Doctor, UX Design, ProjectMapPlanner, Finalizer or Human consume the finding according to routing policy.
