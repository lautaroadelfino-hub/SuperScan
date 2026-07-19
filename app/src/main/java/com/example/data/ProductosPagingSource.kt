package com.example.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

// Pagina una query de `productos` con cursores de Firestore: la clave de cada
// página es el último DocumentSnapshot de la anterior (startAfter). Las
// lecturas resuelven desde la caché offline cuando no hay conexión.
class ProductosPagingSource(
    private val query: Query
) : PagingSource<DocumentSnapshot, ProductModel>() {

    override suspend fun load(
        params: LoadParams<DocumentSnapshot>
    ): LoadResult<DocumentSnapshot, ProductModel> = try {
        val q = params.key?.let { query.startAfter(it) } ?: query
        val docs = q.limit(params.loadSize.toLong()).get().await().documents
        LoadResult.Page(
            data = docs.mapNotNull { it.toObject(ProductModel::class.java)?.copy(ean = it.id) },
            prevKey = null,
            nextKey = if (docs.size < params.loadSize) null else docs.last()
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    // Los cursores son snapshots, no posiciones: no se puede retomar desde el
    // medio tras invalidar; se recarga desde la primera página.
    override fun getRefreshKey(state: PagingState<DocumentSnapshot, ProductModel>): DocumentSnapshot? = null
}
